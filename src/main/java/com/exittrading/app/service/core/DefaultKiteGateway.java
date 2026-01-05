package com.exittrading.app.service.core;

import com.exittrading.app.domain.OrderSide;
import com.exittrading.app.domain.TradingSchedule;
import com.exittrading.app.dto.DepthView;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.OrderParams;
import com.zerodhatech.models.Quote;
import com.zerodhatech.kiteconnect.utils.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default implementation of KiteGateway using the official Kite Connect SDK.
 * Handles order placement, cancellation, and market depth fetching via REST.
 * Refactored to use strong typing and remove reflection.
 */
@Service
@Primary
@ConditionalOnClass(name = "com.zerodhatech.kiteconnect.KiteConnect")
@ConditionalOnProperty(name = "kite.enabled", havingValue = "true", matchIfMissing = true)
public class DefaultKiteGateway implements KiteGateway {

    private static final Logger log = LoggerFactory.getLogger(DefaultKiteGateway.class);

    private final KiteSessionManager sessionManager;
    private final IstClock clock;
    private final InstrumentService instrumentService;
    private final SettingsService settingsService;
    // Sticky max-order tracker for REST depth (persists for process lifetime)
    private final ConcurrentHashMap<String, MaxTracker> maxTrackerByKey = new ConcurrentHashMap<>();

    public DefaultKiteGateway(KiteSessionManager sessionManager, IstClock clock,
                              InstrumentService instrumentService, SettingsService settingsService) {
        this.sessionManager = sessionManager;
        this.clock = clock;
        this.instrumentService = instrumentService;
        this.settingsService = settingsService;
    }

    // Simple in-memory cache to avoid repeatedly scanning instruments
    private final ConcurrentHashMap<String, String> instrumentTokenCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> symbolExchangeCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> tokenSymbolCache = new ConcurrentHashMap<>();


    
    // Simple rate limiter (max 5 orders/sec) without blocking threads
    private static final long ORDER_INTERVAL_MS = 200L;
    private final AtomicLong nextOrderTimeMs = new AtomicLong(0L);
    
    @Override
    public CompletableFuture<String> placePcaOrder(TradingSchedule schedule) {
        return waitForRateLimit().thenCompose(ignored -> CompletableFuture.supplyAsync(() -> {
            try {
                // Strong type: KiteConnect
                KiteConnect kite = sessionManager.getKiteConnect();
                if (kite == null) throw new RuntimeException("Kite session not active");

                OrderParams orderParams = new OrderParams();

                // Required fields
                String[] resolved = resolveExchangeAndSymbol(kite, schedule.getTradingsymbol(), schedule.getInstrumentToken());
                String resolvedExchange = resolved[0] != null ? resolved[0] : defaultExchange();
                String resolvedSymbol = resolved[1] != null ? resolved[1] : schedule.getTradingsymbol();
                
                orderParams.tradingsymbol = resolvedSymbol;
                orderParams.quantity = schedule.getQuantity();

                // Band metadata to avoid circuit rejections for market exits
                Double lowerBand = null, upperBand = null;
                try {
                    InstrumentService.InstrumentMeta meta = instrumentService.find(resolvedExchange, resolvedSymbol, schedule.getInstrumentToken());
                    if (meta != null) {
                        lowerBand = meta.lowerCircuit;
                        upperBand = meta.upperCircuit;
                    }
                } catch (Exception e) {
                    log.debug("Ignored exception: {}", e.getMessage());
                }

                // Transaction and order type
                orderParams.transactionType = schedule.getSide() == OrderSide.BUY ? Constants.TRANSACTION_TYPE_BUY : Constants.TRANSACTION_TYPE_SELL;
                
                boolean isMarket = schedule.getLimitPrice() == null;
                // If hitting circuit on SELL/BUY, clamp to band with LIMIT to avoid 400 from exchange
                if (isMarket && schedule.getSide() == OrderSide.SELL && lowerBand != null) {
                    orderParams.orderType = Constants.ORDER_TYPE_LIMIT;
                    orderParams.price = lowerBand;
                    isMarket = false;
                } else if (isMarket && schedule.getSide() == OrderSide.BUY && upperBand != null) {
                    orderParams.orderType = Constants.ORDER_TYPE_LIMIT;
                    orderParams.price = upperBand;
                    isMarket = false;
                } else {
                    orderParams.orderType = schedule.getLimitPrice() != null ? Constants.ORDER_TYPE_LIMIT : Constants.ORDER_TYPE_MARKET;
                }

                // Misc
                orderParams.exchange = resolvedExchange;
                // Use CNC for delivery (safer default for holdings); fallback to MIS if constant not present
                // Constants.PRODUCT_CNC usually "CNC"
                boolean usedCnc = true;
                orderParams.product = Constants.PRODUCT_CNC;
                orderParams.validity = Constants.VALIDITY_DAY;
                
                if (schedule.getLimitPrice() != null) {
                    orderParams.price = schedule.getLimitPrice().doubleValue();
                } 
                
                orderParams.tag = "PCA";

                // Log the payload minimally for troubleshooting (no secrets)
                String tokenForLog = schedule.getInstrumentToken();
                if (tokenForLog == null || tokenForLog.isBlank()) {
                    try { tokenForLog = resolveInstrumentToken(kite, resolvedExchange + ":" + resolvedSymbol); } catch (Exception e) {
                        log.debug("Ignored exception: {}", e.getMessage());
                    }
                }
                log.info("Placing order: exch={} symbol={} token={} side={} qty={} type={} price={}",
                        resolvedExchange, resolvedSymbol, tokenForLog, schedule.getSide(), schedule.getQuantity(),
                        (schedule.getLimitPrice() != null ? "LIMIT" : "MARKET"), orderParams.price);
                
                Order response;
                try {
                     response = kite.placeOrder(orderParams, Constants.VARIETY_REGULAR);
                } catch (Exception itex) {
                    // If exchange/symbol is fine but product is wrong (e.g., no holdings for CNC SELL), retry with MIS
                    boolean canRetryMis = (schedule.getSide() == OrderSide.SELL);
                    if (canRetryMis) {
                        try {
                            orderParams.product = Constants.PRODUCT_MIS;
                            usedCnc = false;
                            log.info("Retrying order with MIS for {} {}", resolvedExchange, resolvedSymbol);
                            response = kite.placeOrder(orderParams, Constants.VARIETY_REGULAR);
                        } catch (com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException | Exception retryEx) {
                            throw itex; // propagate original
                        }
                    } else {
                        throw itex;
                    }
                }

                return response != null ? response.orderId : "UNKNOWN";
            } catch (com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException | Exception ex) {
                log.error("Order placement failed", ex);
                throw new RuntimeException("Order placement failed: " + ex.getMessage(), ex);
            }
        }, sessionManager.getExecutionPool()));
    }

    private CompletableFuture<Void> waitForRateLimit() {
        long delayMs = computeDelayMs();
        if (delayMs <= 0) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> {}, CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS, sessionManager.getExecutionPool()));
    }

    private long computeDelayMs() {
        long now = System.currentTimeMillis();
        while (true) {
            long prev = nextOrderTimeMs.get();
            long scheduled = Math.max(prev, now);
            long next = scheduled + ORDER_INTERVAL_MS;
            if (nextOrderTimeMs.compareAndSet(prev, next)) {
                return scheduled - now;
            }
        }
    }

    private String[] resolveExchangeAndSymbol(KiteConnect kite, String rawSymbol, String token) {
        String symbol = rawSymbol;
        String exch = null;
        if (symbol != null && symbol.contains(":")) {
            int idx = symbol.indexOf(':');
            exch = symbol.substring(0, idx);
            symbol = symbol.substring(idx + 1);
        }
        if (token != null && !token.isBlank()) {
            String cachedEx = instrumentTokenCache.get(token);
            String cachedSym = tokenSymbolCache.get(token);
            if (cachedEx != null && cachedSym != null) return new String[]{cachedEx, cachedSym};
            String[] resolved = findInstrumentByToken(kite, token);
            if (resolved[0] != null) {
                instrumentTokenCache.putIfAbsent(token, resolved[0]);
                tokenSymbolCache.putIfAbsent(token, resolved[1] != null ? resolved[1] : symbol);
                return resolved;
            }
        }
        if (exch == null) {
            String cached = symbolExchangeCache.get(symbol);
            if (cached != null) return new String[]{cached, symbol};
            // Try NSE then BSE
            String ex = findExchangeBySymbol(kite, symbol);
            if (ex != null) {
                symbolExchangeCache.putIfAbsent(symbol, ex);
                return new String[]{ex, symbol};
            }
        }
        return new String[]{exch, symbol};
    }

    private String[] findInstrumentByToken(KiteConnect kite, String token) {
        try {
            List<com.zerodhatech.models.Instrument> list = kite.getInstruments("NSE");
            for (com.zerodhatech.models.Instrument inst : list) {
                if (String.valueOf(inst.instrument_token).equals(token)) {
                    return new String[]{inst.exchange, inst.tradingsymbol};
                }
            }
             list = kite.getInstruments("BSE");
            for (com.zerodhatech.models.Instrument inst : list) {
                 if (String.valueOf(inst.instrument_token).equals(token)) {
                    return new String[]{inst.exchange, inst.tradingsymbol};
                }
            }
        } catch (com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException | Exception e) {
            log.debug("Ignored exception: {}", e.getMessage());
        }
        return new String[]{null, null};
    }

    private String findExchangeBySymbol(KiteConnect kite, String symbol) {
        try {
            List<com.zerodhatech.models.Instrument> list = kite.getInstruments("NSE");
            for (com.zerodhatech.models.Instrument inst : list) {
                if (inst.tradingsymbol.equalsIgnoreCase(symbol)) return inst.exchange;
            }
             list = kite.getInstruments("BSE");
            for (com.zerodhatech.models.Instrument inst : list) {
                if (inst.tradingsymbol.equalsIgnoreCase(symbol)) return inst.exchange;
            }
        } catch (com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException | Exception e) {
            log.debug("Ignored exception: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public CompletableFuture<com.zerodhatech.models.Order> getOrder(String orderId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (orderId == null) return null;
                KiteConnect kite = sessionManager.getKiteConnect();
                if (kite == null) return null;
                
                // Kite SDK usually fetches ALL orders or specific order history.
                // Single order details: kite.getOrderHistory(orderId) returns list of history. 
                // The last element is the current state.
                List<Order> history = kite.getOrderHistory(orderId);
                if (history == null || history.isEmpty()) return null;
                return history.get(history.size() - 1);
            } catch (com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException | Exception e) {
                log.warn("Failed to fetch order {}: {}", orderId, e.getMessage());
                return null;
            }
        }, sessionManager.getExecutionPool());
    }

    @Override
    public CompletableFuture<Boolean> cancelOrder(String orderId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (orderId == null) return false;
                KiteConnect kite = sessionManager.getKiteConnect();
                if (kite == null) return false;
                
                kite.cancelOrder(orderId, Constants.VARIETY_REGULAR);
                log.info("Cancelled order {}", orderId);
                return true;
            } catch (com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException | Exception e) {
                log.warn("Failed to cancel order {}: {}", orderId, e.getMessage());
                return false;
            }
        }, sessionManager.getExecutionPool());
    }

    @Override
    public CompletableFuture<Void> cancelOpenOrders(String tradingsymbol, OrderSide side) {
        return CompletableFuture.runAsync(() -> {
            try {
                KiteConnect kite = sessionManager.getKiteConnect();
                if (kite == null) return;
                
                List<Order> orders = kite.getOrders();
                if (orders == null) return;
                
                for (Order order : orders) {
                    if (!tradingsymbol.equalsIgnoreCase(order.tradingSymbol) || !"OPEN".equalsIgnoreCase(order.status)) {
                        continue;
                    }
                    if (side != null) {
                        if (side == OrderSide.BUY && !order.transactionType.toUpperCase().contains("BUY")) {
                            continue;
                        }
                        if (side == OrderSide.SELL && !order.transactionType.toUpperCase().contains("SELL")) {
                            continue;
                        }
                    }
                    kite.cancelOrder(order.orderId, Constants.VARIETY_REGULAR);
                    log.info("Cancelled open order {} for {}", order.orderId, tradingsymbol);
                }
            } catch (com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException | Exception ex) {
                log.warn("Failed cancelling open orders for {}: {}", tradingsymbol, ex.getMessage());
            }
        }, sessionManager.getExecutionPool());
    }

    @Override
    public CompletableFuture<DepthView> fetchDepth(String tradingsymbol, String instrumentToken) {
        return CompletableFuture.supplyAsync(() -> {
            if (!sessionManager.isActive()) {
                return null;
            }
            try {
                KiteConnect kite = sessionManager.getKiteConnect();
                if (kite == null) return null;

                // Allow callers to pass fully-qualified instrument (EXCH:SYMBOL)
                String instrument = (tradingsymbol != null && tradingsymbol.contains(":"))
                        ? tradingsymbol
                        : (defaultExchange() + ":" + tradingsymbol);
                
                Map<String, Quote> depthMap = null;
                Throwable lastErr = null;
                // 1) try by instrument
                try {
                    depthMap = kite.getQuote(new String[]{instrument});
                } catch (com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException | Exception e) { lastErr = e; }
                
                // 2) try by provided token
                if ((depthMap == null || depthMap.isEmpty()) && instrumentToken != null && !instrumentToken.isBlank()) {
                    try {
                        depthMap = kite.getQuote(new String[]{instrumentToken});
                    } catch (com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException | Exception e) { lastErr = e; }
                }
                
                // 3) try resolved token
                String resolvedToken = null;
                if (depthMap == null || depthMap.isEmpty()) {
                    try {
                        resolvedToken = resolveInstrumentToken(kite, instrument);
                        if (resolvedToken != null) {
                            depthMap = kite.getQuote(new String[]{resolvedToken});
                        }
                    } catch (com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException | Exception e) { lastErr = e; }
                }

                DepthView view = new DepthView();
                String viewSymbol = tradingsymbol;
                int colon = viewSymbol != null ? viewSymbol.indexOf(':') : -1;
                if (colon > -1 && colon + 1 < viewSymbol.length()) {
                    viewSymbol = viewSymbol.substring(colon + 1);
                }
                view.setTradingsymbol(viewSymbol);
                
                // Enrich circuits
                 try {
                    String exchPart = instrument.contains(":") ? instrument.substring(0, instrument.indexOf(':')) : null;
                    InstrumentService.InstrumentMeta meta = instrumentService.find(exchPart, viewSymbol,
                            instrumentToken != null && !instrumentToken.isBlank() ? instrumentToken : resolvedToken);
                    if (meta != null) {
                        if (meta.tickSize != null) view.setTick(meta.tickSize);
                        if (meta.lowerCircuit != null && view.getLowerCircuit() == null) view.setLowerCircuit(BigDecimal.valueOf(meta.lowerCircuit));
                        if (meta.upperCircuit != null && view.getUpperCircuit() == null) view.setUpperCircuit(BigDecimal.valueOf(meta.upperCircuit));
                    }
                } catch (Exception e) {}

                if (depthMap != null && !depthMap.isEmpty()) {
                    Quote quote = null;
                    if (instrumentToken != null && !instrumentToken.isBlank()) quote = depthMap.get(instrumentToken);
                    if (quote == null && resolvedToken != null) quote = depthMap.get(resolvedToken);
                    if (quote == null) quote = depthMap.get(instrument);
                    if (quote == null && !depthMap.isEmpty()) quote = depthMap.values().iterator().next();
                    
                    if (quote != null) {
                        if (quote.depth != null) {
                             long buyQty = aggregateDepth(quote.depth.buy);
                             long sellQty = aggregateDepth(quote.depth.sell);
                             List<DepthView.Level> buyLevels = mapLevels(quote.depth.buy);
                             List<DepthView.Level> sellLevels = mapLevels(quote.depth.sell);
                             view.setBuyQuantity(buyQty);
                             view.setSellQuantity(sellQty);
                             view.setBuyLevels(buyLevels);
                             view.setSellLevels(sellLevels);
                             
                             MaxTracker tracker = maxTrackerByKey.computeIfAbsent(keyFor(instrument, instrumentToken, resolvedToken), k -> new MaxTracker());
                             tracker.updateFromTop(!buyLevels.isEmpty() ? buyLevels.get(0) : null, !sellLevels.isEmpty() ? sellLevels.get(0) : null);
                             MaxOrderInfo maxBid = tracker.getBid();
                             MaxOrderInfo maxAsk = tracker.getAsk();
                             if (maxBid != null) {
                                  view.setMaxBuyOrderQty(maxBid.qty);
                                  view.setMaxBuyOrderCount(maxBid.orders);
                                  view.setMaxBuyOrderPrice(maxBid.price);
                             }
                             if (maxAsk != null) {
                                  view.setMaxSellOrderQty(maxAsk.qty);
                                  view.setMaxSellOrderCount(maxAsk.orders);
                                  view.setMaxSellOrderPrice(maxAsk.price);
                             }
                        }
                        
                        view.setLtp(BigDecimal.valueOf(quote.lastPrice));
                        if (quote.ohlc != null) {
                            view.setOpen(BigDecimal.valueOf(quote.ohlc.open));
                            view.setHigh(BigDecimal.valueOf(quote.ohlc.high));
                            view.setLow(BigDecimal.valueOf(quote.ohlc.low));
                            view.setPrevClose(BigDecimal.valueOf(quote.ohlc.close));
                        }
                        view.setVolume((long) quote.volumeTradedToday);
                        view.setAvgPrice(BigDecimal.valueOf(quote.averagePrice));
                        view.setLowerCircuit(BigDecimal.valueOf(quote.lowerCircuitLimit));
                        view.setUpperCircuit(BigDecimal.valueOf(quote.upperCircuitLimit));
                        view.setLtq((long) quote.lastTradedQuantity);
                        if (quote.lastTradedTime != null) view.setLtt(quote.lastTradedTime.toString());
                    }
                } else {
                     // Fallback: try getLTP/getOHLC
                    try {
                        Map<String, com.zerodhatech.models.LTPQuote> ltpMap = kite.getLTP(new String[]{instrument});
                        if (ltpMap != null && ltpMap.get(instrument) != null) {
                            view.setLtp(BigDecimal.valueOf(ltpMap.get(instrument).lastPrice));
                        }
                        Map<String, com.zerodhatech.models.OHLCQuote> ohlcMap = kite.getOHLC(new String[]{instrument});
                        if (ohlcMap != null && ohlcMap.get(instrument) != null) {
                             com.zerodhatech.models.OHLCQuote q = ohlcMap.get(instrument);
                             if (q.ohlc != null) {
                                 view.setOpen(BigDecimal.valueOf(q.ohlc.open));
                                 view.setHigh(BigDecimal.valueOf(q.ohlc.high));
                                 view.setLow(BigDecimal.valueOf(q.ohlc.low));
                                 view.setPrevClose(BigDecimal.valueOf(q.ohlc.close));
                             }
                        }
                    } catch (com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException | Exception e) {}
                }
                
                view.setCapturedAt(clock.now());
                return view;
            } catch (Exception ex) {
                log.warn("Depth fetch failed for {}: {}", tradingsymbol, ex.getMessage());
                DepthView fallback = new DepthView();
                fallback.setTradingsymbol(tradingsymbol);
                fallback.setCapturedAt(clock.now());
                return fallback;
            }
        }, sessionManager.getMarketDataPool());
    }

    private String resolveInstrumentToken(KiteConnect kite, String instrument) {
        try {
            if (instrument == null) return null;
            String cached = instrumentTokenCache.get(instrument);
            if (cached != null) return cached;

            String exch = instrument.contains(":") ? instrument.substring(0, instrument.indexOf(':')) : defaultExchange();
            String symbol = instrument.contains(":") ? instrument.substring(instrument.indexOf(':') + 1) : instrument;
            
            // This is heavy, but better than reflection. 
            // Ideally we rely on InstrumentService for this, but this method is kept self-contained
            List<com.zerodhatech.models.Instrument> instruments = kite.getInstruments(exch);
            for (com.zerodhatech.models.Instrument inst : instruments) {
                if (inst.exchange.equalsIgnoreCase(exch) && inst.tradingsymbol.equalsIgnoreCase(symbol)) {
                    String token = String.valueOf(inst.instrument_token);
                    instrumentTokenCache.put(instrument, token);
                    return token;
                }
            }
        } catch (com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException | Exception ignored) {
        }
        return null;
    }

    private long aggregateDepth(List<com.zerodhatech.models.Depth> levels) {
        if (levels == null) return 0;
        long total = 0;
        for (com.zerodhatech.models.Depth d : levels) {
            total += d.getQuantity();
        }
        return total;
    }

    private String keyFor(String instrument, String instrumentToken, String resolvedToken){
        if (instrumentToken != null && !instrumentToken.isBlank()) return instrumentToken;
        if (resolvedToken != null && !resolvedToken.isBlank()) return resolvedToken;
        return instrument != null ? instrument : "unknown";
    }

    private static class MaxOrderInfo {
        final Long qty;
        final Integer orders;
        final Double price;
        MaxOrderInfo(Long qty, Integer orders, Double price) { this.qty = qty; this.orders = orders; this.price = price; }
    }
    private static class MaxTracker {
        private static class PriceState {
            long lastQty;
            int lastOrders;
            long maxDeltaQty;
            int maxDeltaOrders;
            final double price;
            PriceState(double price, long lastQty, int lastOrders) {
                this.price = price;
                this.lastQty = lastQty;
                this.lastOrders = lastOrders;
                this.maxDeltaQty = 0;
                this.maxDeltaOrders = 1;
            }
        }
        private final Map<Double, PriceState> bidStates = new ConcurrentHashMap<>();
        private final Map<Double, PriceState> askStates = new ConcurrentHashMap<>();

        synchronized void updateFromTop(DepthView.Level topBid, DepthView.Level topAsk){
            if (topBid != null) updateSide(bidStates, topBid);
            if (topAsk != null) updateSide(askStates, topAsk);
        }

        private void updateSide(Map<Double, PriceState> map, DepthView.Level lvl){
            double px = lvl.getPrice();
            long qty = lvl.getQuantity();
            int orders = lvl.getOrders();
            PriceState st = map.get(px);
            if (st == null) {
                st = new PriceState(px, qty, orders);
                st.maxDeltaQty = 0;
                st.maxDeltaOrders = 1;
                map.put(px, st);
                return; 
            }
            long deltaQty = qty - st.lastQty;
            int deltaOrders = Math.max(1, orders - st.lastOrders);
            if (deltaQty > st.maxDeltaQty) {
                st.maxDeltaQty = deltaQty;
                st.maxDeltaOrders = deltaOrders;
            }
            st.lastQty = qty;
            st.lastOrders = orders;
        }

        private MaxOrderInfo best(Map<Double, PriceState> map){
            PriceState best = null;
            for (PriceState st : map.values()) {
                if (st == null) continue;
                if (best == null || st.maxDeltaQty > best.maxDeltaQty) best = st;
            }
            if (best == null || best.maxDeltaQty <= 0) return null;
            return new MaxOrderInfo(best.maxDeltaQty, best.maxDeltaOrders, best.price);
        }

        synchronized MaxOrderInfo getBid(){ return best(bidStates); }
        synchronized MaxOrderInfo getAsk(){ return best(askStates); }
    }

    private List<DepthView.Level> mapLevels(List<com.zerodhatech.models.Depth> levels) {
        if (levels == null) return Collections.emptyList();
        int n = Math.min(5, levels.size());
        java.util.ArrayList<DepthView.Level> out = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            com.zerodhatech.models.Depth d = levels.get(i);
            out.add(new DepthView.Level(d.getQuantity(), d.getPrice(), d.getOrders()));
        }
        return out;
    }

    private String defaultExchange() {
        return settingsService.getString("kite.default.exchange", "NSE");
    }
}
