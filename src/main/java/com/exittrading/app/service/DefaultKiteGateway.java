package com.exittrading.app.service;

import com.exittrading.app.domain.OrderSide;
import com.exittrading.app.domain.TradingSchedule;
import com.exittrading.app.dto.DepthView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
@Primary
@ConditionalOnClass(name = "com.zerodhatech.kiteconnect.KiteConnect")
public class DefaultKiteGateway implements KiteGateway {

    private static final Logger log = LoggerFactory.getLogger(DefaultKiteGateway.class);

    private final KiteSessionManager sessionManager;
    private final IstClock clock;

    @Value("${kite.default.exchange:NSE}")
    private String exchange;

    public DefaultKiteGateway(KiteSessionManager sessionManager, IstClock clock) {
        this.sessionManager = sessionManager;
        this.clock = clock;
    }

    // Simple in-memory cache to avoid repeatedly scanning instruments
    private final java.util.concurrent.ConcurrentHashMap<String, String> instrumentTokenCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, String> symbolExchangeCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, String> tokenSymbolCache = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public CompletableFuture<String> placePcaOrder(TradingSchedule schedule) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Object kite = sessionManager.getKiteConnect();
                Class<?> orderParamsClass = Class.forName("com.zerodhatech.models.OrderParams");
                Object orderParams = orderParamsClass.getConstructor().newInstance();

                // Required fields
                String[] resolved = resolveExchangeAndSymbol(kite, schedule.getTradingsymbol(), schedule.getInstrumentToken());
                String resolvedExchange = resolved[0] != null ? resolved[0] : exchange;
                String resolvedSymbol = resolved[1] != null ? resolved[1] : schedule.getTradingsymbol();
                setField(orderParamsClass, orderParams, "tradingsymbol", resolvedSymbol);
                setField(orderParamsClass, orderParams, "quantity", schedule.getQuantity());

                // Transaction and order type
                Object txnBuy = safeStaticField(orderParamsClass, "TRANSACTION_TYPE_BUY", "BUY");
                Object txnSell = safeStaticField(orderParamsClass, "TRANSACTION_TYPE_SELL", "SELL");
                Object ordLimit = safeStaticField(orderParamsClass, "ORDER_TYPE_LIMIT", "LIMIT");
                Object ordMarket = safeStaticField(orderParamsClass, "ORDER_TYPE_MARKET", "MARKET");
                setField(orderParamsClass, orderParams, "transactionType",
                        schedule.getSide() == OrderSide.BUY ? txnBuy : txnSell);
                setField(orderParamsClass, orderParams, "orderType",
                        schedule.getLimitPrice() != null ? ordLimit : ordMarket);

                // Misc
                setField(orderParamsClass, orderParams, "exchange", resolvedExchange);
                // Use CNC for delivery (safer default for holdings); fallback to MIS if constant not present
                Object productCnc = safeStaticField(orderParamsClass, "PRODUCT_CNC", "CNC");
                Object productMis = safeStaticField(orderParamsClass, "PRODUCT_MIS", "MIS");
                boolean usedCnc = true;
                setField(orderParamsClass, orderParams, "product", productCnc);
                setField(orderParamsClass, orderParams, "validity",
                        safeStaticField(orderParamsClass, "VALIDITY_DAY", "DAY"));
                if (schedule.getLimitPrice() != null) {
                    setField(orderParamsClass, orderParams, "price", schedule.getLimitPrice().doubleValue());
                } else {
                    // MARKET protection required by Kite on some scrips; -1 lets backend auto-apply
                    try { setField(orderParamsClass, orderParams, "marketProtection", -1); } catch (Exception ignored) {}
                }
                setField(orderParamsClass, orderParams, "tag", "PCA");

                // placeOrder(orderParams, variety)
                Method placeOrder = kite.getClass().getMethod("placeOrder", orderParamsClass, String.class);
                Object varietyConst = safeStaticField(Class.forName("com.zerodhatech.kiteconnect.KiteConnect"),
                        "VARIETY_REGULAR", "regular");
                // Log the payload minimally for troubleshooting (no secrets)
                String tokenForLog = schedule.getInstrumentToken();
                if (tokenForLog == null || tokenForLog.isBlank()) {
                    try { tokenForLog = resolveInstrumentToken(kite, resolvedExchange + ":" + resolvedSymbol); } catch (Exception ignored) {}
                }
                log.info("Placing order: exch={} symbol={} token={} side={} qty={} type={} price={}",
                        resolvedExchange, resolvedSymbol, tokenForLog, schedule.getSide(), schedule.getQuantity(),
                        (schedule.getLimitPrice() != null ? "LIMIT" : "MARKET"), schedule.getLimitPrice());
                Object response;
                try {
                    response = placeOrder.invoke(kite, orderParams, String.valueOf(varietyConst));
                } catch (java.lang.reflect.InvocationTargetException itex) {
                    Throwable cause = itex.getTargetException();
                    // If exchange/symbol is fine but product is wrong (e.g., no holdings for CNC SELL), retry with MIS
                    boolean canRetryMis = (schedule.getSide() == OrderSide.SELL);
                    if (canRetryMis) {
                        try {
                            setField(orderParamsClass, orderParams, "product", productMis);
                            usedCnc = false;
                            log.info("Retrying order with MIS for {} {}", resolvedExchange, resolvedSymbol);
                            response = placeOrder.invoke(kite, orderParams, String.valueOf(varietyConst));
                        } catch (Exception retryEx) {
                            throw itex; // propagate original
                        }
                    } else {
                        throw itex;
                    }
                }

                if (response instanceof Map<?, ?> map) {
                    Object orderId = map.get("order_id");
                    return orderId != null ? orderId.toString() : "UNKNOWN";
                }
                try {
                    Class<?> orderClass = Class.forName("com.zerodhatech.models.Order");
                    if (orderClass.isInstance(response)) {
                        Object orderId = getField(orderClass, response, "orderId");
                        return orderId != null ? orderId.toString() : "UNKNOWN";
                    }
                } catch (ClassNotFoundException ignored) {
                    // Fall through
                }
                return "UNKNOWN";
            } catch (java.lang.reflect.InvocationTargetException itex) {
                Throwable cause = itex.getTargetException();
                log.error("Order placement failed: {} {}", describeKiteException(cause), (cause != null ? cause.getMessage() : "no-message"));
                throw new RuntimeException("Order placement failed: " + (cause != null ? cause.getMessage() : null), itex);
            } catch (Exception ex) {
                log.error("Order placement failed", ex);
                throw new RuntimeException("Order placement failed: " + ex.getMessage(), ex);
            }
        }, sessionManager.getExecutionPool());
    }

    private String[] resolveExchangeAndSymbol(Object kite, String rawSymbol, String token) {
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

    private String[] findInstrumentByToken(Object kite, String token) {
        try {
            Method getInstruments = kite.getClass().getMethod("getInstruments", String.class);
            for (String ex : new String[]{"NSE", "BSE"}) {
                Object listObj = getInstruments.invoke(kite, ex);
                if (!(listObj instanceof java.util.List<?> list)) continue;
                for (Object inst : list) {
                    Class<?> c = inst.getClass();
                    Object tok = getField(c, inst, "instrument_token");
                    if (tok == null) tok = getField(c, inst, "instrumentToken");
                    if (tok != null && token.equals(String.valueOf(tok))) {
                        Object sym = getField(c, inst, "tradingsymbol");
                        if (sym == null) sym = getField(c, inst, "tradingSymbol");
                        return new String[]{ex, sym != null ? String.valueOf(sym) : null};
                    }
                }
            }
        } catch (Exception ignored) {}
        return new String[]{null, null};
    }

    private String findExchangeBySymbol(Object kite, String symbol) {
        try {
            Method getInstruments = kite.getClass().getMethod("getInstruments", String.class);
            for (String ex : new String[]{"NSE", "BSE"}) {
                Object listObj = getInstruments.invoke(kite, ex);
                if (!(listObj instanceof java.util.List<?> list)) continue;
                for (Object inst : list) {
                    Class<?> c = inst.getClass();
                    Object sym = getField(c, inst, "tradingsymbol");
                    if (sym == null) sym = getField(c, inst, "tradingSymbol");
                    if (sym != null && symbol.equalsIgnoreCase(String.valueOf(sym))) {
                        return ex;
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Override
    public CompletableFuture<Void> cancelOpenOrders(String tradingsymbol, OrderSide side) {
        return CompletableFuture.runAsync(() -> {
            try {
                Object kite = sessionManager.getKiteConnect();
                Method getOrders = kite.getClass().getMethod("getOrders");
                Object result = getOrders.invoke(kite);
                List<?> orders = result instanceof List<?> list ? list : Collections.emptyList();
                for (Object order : orders) {
                    Class<?> orderClass = order.getClass();
                    String symbol = String.valueOf(getField(orderClass, order, "tradingsymbol"));
                    String status = String.valueOf(getField(orderClass, order, "status"));
                    if (!tradingsymbol.equalsIgnoreCase(symbol) || !"OPEN".equalsIgnoreCase(status)) {
                        continue;
                    }
                    if (side != null) {
                        String transactionType = String.valueOf(getField(orderClass, order, "transactionType"));
                        if (side == OrderSide.BUY && !transactionType.toUpperCase().contains("BUY")) {
                            continue;
                        }
                        if (side == OrderSide.SELL && !transactionType.toUpperCase().contains("SELL")) {
                            continue;
                        }
                    }
                    String orderId = String.valueOf(getField(orderClass, order, "orderId"));
                    String variety = String.valueOf(getField(orderClass, order, "variety"));
                    Method cancelOrder = kite.getClass().getMethod("cancelOrder", String.class, String.class);
                    cancelOrder.invoke(kite, orderId, variety);
                    log.info("Cancelled open order {} for {}", orderId, tradingsymbol);
                }
            } catch (Exception ex) {
                log.warn("Failed cancelling open orders for {}: {}", tradingsymbol, ex.getMessage());
            }
        }, sessionManager.getExecutionPool());
    }

    @Override
    public CompletableFuture<DepthView> fetchDepth(String tradingsymbol, String instrumentToken) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Object kite = sessionManager.getKiteConnect();
                // Allow callers to pass fully-qualified instrument (EXCH:SYMBOL)
                String instrument = (tradingsymbol != null && tradingsymbol.contains(":"))
                        ? tradingsymbol
                        : (exchange + ":" + tradingsymbol);
                Method getQuote = kite.getClass().getMethod("getQuote", String[].class);
                String key = (instrumentToken != null && !instrumentToken.isBlank()) ? instrumentToken : instrument;
                // Query using both token and instrument if we have them
                String[] queryKeys = key.equals(instrument) ? new String[]{ key } : new String[]{ key, instrument };
                log.info("Depth fetch keys={} token={} instrument={}", java.util.Arrays.toString(queryKeys), instrumentToken, instrument);
                Object depthResult = getQuote.invoke(kite, (Object) queryKeys);
                DepthView view = new DepthView();
                String viewSymbol = tradingsymbol;
                int colon = viewSymbol != null ? viewSymbol.indexOf(':') : -1;
                if (colon > -1 && colon + 1 < viewSymbol.length()) {
                    viewSymbol = viewSymbol.substring(colon + 1);
                }
                view.setTradingsymbol(viewSymbol);
                if (depthResult instanceof Map<?, ?> depthMap) {
                    log.info("Depth map size={} keys={}", depthMap.size(), depthMap.keySet());
                    Object quote = depthMap.get(key);
                    if (quote == null && depthMap.size() > 0) {
                        // Try the alternate key (instrument) if token lookup failed
                        quote = depthMap.get(instrument);
                    }
                    if (quote == null && (instrumentToken == null || instrumentToken.isBlank())) {
                        // Try to resolve token for EXCH:SYMBOL and retry once
                        try {
                            String resolved = resolveInstrumentToken(kite, instrument);
                            if (resolved != null) {
                                depthResult = getQuote.invoke(kite, (Object) new String[]{resolved, instrument});
                                Map<?,?> m = (Map<?,?>) depthResult;
                                quote = m.get(resolved);
                                if (quote == null) quote = m.get(instrument);
                                key = resolved;
                                log.info("Depth retry with resolved token {} => hit={}", resolved, quote != null);
                            }
                        } catch (Exception ignore) {}
                    }
                    if (quote != null) {
                        Class<?> quoteClass = quote.getClass();
                        Object depth = getField(quoteClass, quote, "depth");
                        if (depth != null) {
                            Class<?> depthClass = depth.getClass();
                            Object buyLevels = getField(depthClass, depth, "buy");
                            Object sellLevels = getField(depthClass, depth, "sell");
                            long buyQty = aggregateDepth(buyLevels);
                            long sellQty = aggregateDepth(sellLevels);
                            view.setBuyQuantity(buyQty);
                            view.setSellQuantity(sellQty);

                            // Map top 5 levels into DTO if available
                            view.setBuyLevels(mapLevels(buyLevels));
                            view.setSellLevels(mapLevels(sellLevels));
                        }
                        Number lastPrice = (Number) getField(quoteClass, quote, "lastPrice");
                        if (lastPrice == null) {
                            lastPrice = (Number) getField(quoteClass, quote, "lastTradedPrice");
                        }
                        if (lastPrice != null) {
                            view.setLtp(BigDecimal.valueOf(lastPrice.doubleValue()));
                        }
                        // Summary values
                        Object ohlc = getField(quoteClass, quote, "ohlc");
                        if (ohlc != null) {
                            Number open = (Number) getField(ohlc.getClass(), ohlc, "open");
                            Number high = (Number) getField(ohlc.getClass(), ohlc, "high");
                            Number low = (Number) getField(ohlc.getClass(), ohlc, "low");
                            Number close = (Number) getField(ohlc.getClass(), ohlc, "close");
                            if (open != null) view.setOpen(BigDecimal.valueOf(open.doubleValue()));
                            if (high != null) view.setHigh(BigDecimal.valueOf(high.doubleValue()));
                            if (low != null) view.setLow(BigDecimal.valueOf(low.doubleValue()));
                            if (close != null) view.setPrevClose(BigDecimal.valueOf(close.doubleValue()));
                        }
                        Number vol = (Number) getField(quoteClass, quote, "volumeTradedToday");
                        if (vol == null) vol = (Number) getField(quoteClass, quote, "volume");
                        if (vol != null) view.setVolume(vol.longValue());
                        Number avg = (Number) getField(quoteClass, quote, "averagePrice");
                        if (avg != null) view.setAvgPrice(BigDecimal.valueOf(avg.doubleValue()));
                        Number lcl = (Number) getField(quoteClass, quote, "lowerCircuitLimit");
                        if (lcl != null) view.setLowerCircuit(BigDecimal.valueOf(lcl.doubleValue()));
                        Number ucl = (Number) getField(quoteClass, quote, "upperCircuitLimit");
                        if (ucl != null) view.setUpperCircuit(BigDecimal.valueOf(ucl.doubleValue()));
                        Number ltq = (Number) getField(quoteClass, quote, "lastTradedQuantity");
                        if (ltq == null) ltq = (Number) getField(quoteClass, quote, "lastQuantity");
                        if (ltq != null) view.setLtq(ltq.longValue());
                        Object ltt = getField(quoteClass, quote, "lastTradedTime");
                        if (ltt == null) ltt = getField(quoteClass, quote, "timestamp");
                        if (ltt != null) view.setLtt(String.valueOf(ltt));
                    } else {
                        log.warn("Depth fetch returned no quote for keys token={} instrument={}", instrumentToken, instrument);
                    }
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

    private String resolveInstrumentToken(Object kite, String instrument) {
        try {
            if (instrument == null) return null;
            // Cache lookup
            String cached = instrumentTokenCache.get(instrument);
            if (cached != null) return cached;

            String exch = instrument.contains(":") ? instrument.substring(0, instrument.indexOf(':')) : exchange;
            String symbol = instrument.contains(":") ? instrument.substring(instrument.indexOf(':') + 1) : instrument;
            Method getInstruments = kite.getClass().getMethod("getInstruments", String.class);
            Object listObj = getInstruments.invoke(kite, exch);
            if (!(listObj instanceof java.util.List<?> list)) return null;
            for (Object inst : list) {
                Class<?> c = inst.getClass();
                Object exchField = getField(c, inst, "exchange");
                Object symField = getField(c, inst, "tradingsymbol");
                if (symField == null) symField = getField(c, inst, "tradingSymbol");
                if (exchField != null && symField != null && exch.equalsIgnoreCase(String.valueOf(exchField))
                        && symbol.equalsIgnoreCase(String.valueOf(symField))) {
                    Object tok = getField(c, inst, "instrument_token");
                    if (tok == null) tok = getField(c, inst, "instrumentToken");
                    if (tok == null) tok = getField(c, inst, "instrumenttoken");
                    if (tok != null) {
                        String token = String.valueOf(tok);
                        instrumentTokenCache.put(instrument, token);
                        return token;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private long aggregateDepth(Object levels) {
        if (!(levels instanceof List<?> list)) {
            return 0;
        }
        long total = 0;
        for (Object item : list) {
            Object quantity = getField(item.getClass(), item, "quantity");
            if (quantity instanceof Number number) {
                total += number.longValue();
            }
        }
        return total;
    }

    private List<DepthView.Level> mapLevels(Object levels) {
        try {
            if (!(levels instanceof List<?> list)) return Collections.emptyList();
            int n = Math.min(5, list.size());
            java.util.ArrayList<DepthView.Level> out = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                Object lv = list.get(i);
                Class<?> t = lv.getClass();
                Number qty = (Number) getField(t, lv, "quantity");
                Number price = (Number) getField(t, lv, "price");
                Number orders = (Number) getField(t, lv, "orders");
                out.add(new DepthView.Level(qty != null ? qty.intValue() : 0,
                        price != null ? price.doubleValue() : 0.0,
                        orders != null ? orders.intValue() : 0));
            }
            return out;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private void setField(Class<?> type, Object target, String fieldName, Object value) throws Exception {
        Field field = type.getField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Object getField(Class<?> type, Object target, String fieldName) {
        try {
            Field field = type.getField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (Exception ignoredPublic) {
            try {
                Field df = type.getDeclaredField(fieldName);
                df.setAccessible(true);
                return df.get(target);
            } catch (Exception ignoredPrivate) {
                return null;
            }
        }
    }

    private String describeKiteException(Throwable t) {
        if (t == null) return "";
        try {
            Class<?> c = t.getClass();
            Object code = getField(c, t, "code");
            Object status = getField(c, t, "status");
            Object errorType = getField(c, t, "errorType");
            Object message = getField(c, t, "message");
            Object data = getField(c, t, "data");
            return String.format("[code=%s status=%s type=%s msg=%s data=%s]",
                    String.valueOf(code), String.valueOf(status), String.valueOf(errorType), String.valueOf(message), String.valueOf(data));
        } catch (Exception ignore) {
            return "";
        }
    }

    private Object getStaticField(Class<?> type, String name) throws Exception {
        Field field = type.getField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private Object safeStaticField(Class<?> type, String name, Object fallback) {
        try {
            return getStaticField(type, name);
        } catch (Exception e) {
            return fallback;
        }
    }
}
