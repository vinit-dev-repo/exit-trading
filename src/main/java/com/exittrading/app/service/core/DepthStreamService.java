package com.exittrading.app.service.core;

import com.exittrading.app.domain.UserAccount;
import com.exittrading.app.dto.DepthView;
import com.exittrading.app.logging.ScripLogFormatter;
import com.exittrading.app.service.util.UserAccountUtil;
import com.zerodhatech.models.Tick;
import com.zerodhatech.ticker.KiteTicker;
import com.zerodhatech.ticker.OnConnect;
import com.zerodhatech.ticker.OnDisconnect;
import com.zerodhatech.ticker.OnError;
import com.zerodhatech.ticker.OnTicks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Service to manage real-time depth streaming via Kite Ticker.
 * Handles connection, subscription, and processing of tick data.
 * Refactored to use strong typing, remove reflection, and unify suppression logic.
 */
@Service
public class DepthStreamService {

    private static final Logger log = LoggerFactory.getLogger(DepthStreamService.class);
    private static final Logger tlog = LoggerFactory.getLogger("ticker");

    private final KiteSessionManager sessionManager;
    private final InstrumentService instrumentService;
    private final SettingsService settingsService;

    private KiteTicker ticker;
    private final Map<String, DepthView> cacheByToken = new ConcurrentHashMap<>();
    private final Map<String, String> tokenToSymbol = new ConcurrentHashMap<>();
    private final Map<String, String> tokenToInstrument = new ConcurrentHashMap<>();
    private final Map<String, Deque<HistorySample>> historyByToken = new ConcurrentHashMap<>();
    private final Map<String, MaxTracker> maxTrackerByToken = new ConcurrentHashMap<>();

    private volatile boolean disabled = false;
    private volatile String lastError = null;
    private volatile String lastAccessToken = null;
    private volatile UserAccount lastUser = null; 
    private final ScheduledExecutorService retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r);
        t.setName("depth-retry-" + t.getId());
        t.setUncaughtExceptionHandler((thr, ex) -> log.error("Uncaught exception in {}", thr.getName(), ex));
        return t;
    });
    

    // Coalescing Service handles persistence
    private final CoalescingPersistenceService coalescingPersistenceService;


    private final DepthService depthService;
    private final com.exittrading.app.repository.LoggingScripRepository loggingScripRepository;
    private final com.exittrading.app.service.scrip.LoggingScripService loggingScripService;


    public DepthStreamService(KiteSessionManager sessionManager, 
                              InstrumentService instrumentService, 
                              DepthService depthService,
                              com.exittrading.app.repository.LoggingScripRepository loggingScripRepository,
                              @Lazy com.exittrading.app.service.scrip.LoggingScripService loggingScripService,
                              CoalescingPersistenceService coalescingPersistenceService,
                              SettingsService settingsService) {
        this.sessionManager = sessionManager;
        this.instrumentService = instrumentService;
        this.depthService = depthService;
        this.loggingScripRepository = loggingScripRepository;
        this.loggingScripService = loggingScripService;
        this.coalescingPersistenceService = coalescingPersistenceService;
        this.settingsService = settingsService;
    }
    
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        log.info("Shutting down DepthStreamService");
        if (ticker != null) {
            try { ticker.disconnect(); } catch (Exception e) {/*ignore*/}
        }
        try {
            retryScheduler.shutdownNow();
        } catch (Exception e) {
            log.warn("Failed to shutdown retry scheduler: {}", e.getMessage());
        }

    }

    public synchronized void startForUser(UserAccount user) {
        this.lastUser = user;
        try {
            try { loggingScripService.syncHoldings(user.getUsername()); } catch(Exception e) { log.warn("Failed to sync holdings: {}", e.getMessage()); }
            
            if (ticker != null) return;
            if (disabled) {
                try {
                    com.zerodhatech.kiteconnect.KiteConnect kite = sessionManager.getKiteConnect();
                    String currentToken = kite != null ? kite.getAccessToken() : null;
                    if (currentToken != null && !currentToken.equals(lastAccessToken)) {
                        log.info("Depth stream retry enabled due to new access token");
                        disabled = false;
                    } else {
                        log.warn("Depth stream disabled due to previous handshake error; using REST fallbacks");
                        return;
                    }
                } catch (Exception e) {
                    log.warn("Depth stream disabled and no session available");
                    return;
                }
            }
            com.zerodhatech.kiteconnect.KiteConnect kite = sessionManager.getKiteConnect();
            if (kite == null) throw new RuntimeException("Kite session is null");

            String accessToken = kite.getAccessToken();
            String apiKey = kite.getApiKey();
            lastAccessToken = accessToken;
            
            ticker = new KiteTicker(accessToken, apiKey);
            ticker.setTryReconnection(true);
            ticker.setMaximumRetries(10);
            ticker.setMaximumRetryInterval(30);

            ticker.setOnTickerArrivalListener(new OnTicks() {
                @Override
                public void onTicks(ArrayList<Tick> ticks) {
                    handleTicks(ticks);
                }
            });

            ticker.setOnConnectedListener(new OnConnect() {
                @Override
                public void onConnected() {
                    try { subscribeHoldings(user); } catch (Exception e) { log.warn("Depth stream subscribe failed: {}", e.getMessage()); }
                }
            });

            ticker.setOnErrorListener(new OnError() {
                @Override
                public void onError(com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException e) {
                     log.warn("Depth stream error: {}", (e != null ? e.getMessage() : "?"));
                }
                @Override
                public void onError(Exception e) {
                     log.warn("Depth stream error: {}", (e != null ? e.getMessage() : "?"));
                }
                @Override
                public void onError(String error) {
                     log.warn("Depth stream error: {}", error);
                }
            });

            ticker.setOnDisconnectedListener(new OnDisconnect() {
                @Override
                public void onDisconnected() {
                    log.info("Depth stream disconnected");
                }
            });
            
            try {
                ticker.connect();
            } catch (Exception rte) {
                lastError = rte.getMessage();
                String msg = lastError != null ? lastError : String.valueOf(rte);
                if (msg.contains("403") || msg.contains("Forbidden")) {
                    disabled = true;
                    log.warn("Depth stream handshake rejected (403). Disabling streaming until fresh login; falling back to REST.");
                    ticker = null;
                    return;
                }
                throw rte;
            }
            log.info("Depth stream connected (requested)");
        } catch (com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException | Exception e) {
            lastError = e.getMessage();
            log.warn("Unable to start depth stream: {}", e.getMessage());
            scheduleRetry();
        }
    }

    private void scheduleRetry() {
        try {
            retryScheduler.schedule(() -> {
                try {
                    log.info("Retrying depth stream connection after backoff");
                    disabled = false;
                    if (lastUser != null) {
                        startForUser(lastUser);
                    }
                } catch (Exception e) { 
                    log.error("Retry failed", e);
                }
            }, 5, TimeUnit.SECONDS);        
        } catch (Exception e) {
             log.error("Failed to schedule retry", e);
        }
    }

    private void subscribeHoldings(UserAccount user) throws Exception {
        Set<Long> tokens = new HashSet<>();
        
        // 1. Add Active Logging Scrips from DB
        List<com.exittrading.app.domain.LoggingScrip> scrips = loggingScripRepository.findByActiveTrue();
        for (com.exittrading.app.domain.LoggingScrip s : scrips) {
            if (s.getInstrumentToken() != null) {
                try {
                    long t = Long.parseLong(s.getInstrumentToken());
                    tokens.add(t);
                    tokenToSymbol.put(String.valueOf(t), s.getTradingsymbol());
                    tokenToInstrument.put(String.valueOf(t), s.getExchange() + ":" + s.getTradingsymbol());
                } catch (Exception e) {}
            }
        }
        
        // 2. Add Holdings
        if (user.getHoldings() != null) {
            for (String h : user.getHoldings()) {
                try {
                    // correct parsing of EXCH:SYMBOL|...
                    String[] parts = h.split("\\|");
                    String first = parts.length > 0 ? parts[0] : "";
                    String exchange = "NSE";
                    String symbol = null;
                    
                    if (first.contains(":")) {
                        String[] split = first.split(":");
                        exchange = split[0];
                        symbol = split[1];
                    } else {
                        // fallback or just symbol
                        symbol = UserAccountUtil.parseSymbol(h);
                    }

                    Long t = UserAccountUtil.parseToken(h);
                    
                    // Fallback: If token missing in holding string, lookup in generic InstrumentService
                    if (t == null && symbol != null) {
                        try {
                            InstrumentService.InstrumentMeta meta = instrumentService.find(exchange, symbol, null);
                            if (meta != null && meta.token != null) {
                                t = Long.parseLong(meta.token);
                            }
                        } catch(Exception e) {}
                    }
                    
                    if (t != null) {
                        tokens.add(t);
                        if (symbol != null) {
                            tokenToSymbol.put(String.valueOf(t), symbol);
                            tokenToInstrument.put(String.valueOf(t), exchange + ":" + symbol);
                        }
                    }
                } catch (Exception e) {}
            }
        }
        
        // 3. Last Resort: Resolve unknown symbols via InstrumentService (for DB scrips that might have lacked metadata)
        for (Long t : tokens) {
            String ts = String.valueOf(t);
            if (!tokenToSymbol.containsKey(ts)) {
                InstrumentService.InstrumentMeta meta = instrumentService.findByToken(ts);
                if (meta != null && meta.symbol != null) {
                    tokenToSymbol.put(ts, meta.symbol);
                    tokenToInstrument.put(ts, meta.exchange + ":" + meta.symbol);
                }
            }
        }
        
        if (tokens.isEmpty()) return;
        // Gating: Limit to 200 subscriptions on startup to prevent overload
        List<Long> limitedList = new ArrayList<>(tokens);
        if (limitedList.size() > 200) {
            log.warn("Subscription count ({}) exceeds limit of 200. Truncating.", limitedList.size());
            limitedList = limitedList.subList(0, 200);
        }
        subscribe(limitedList);
    }

    public void subscribe(List<Long> tokenList) {
        if (ticker == null || disabled || tokenList == null || tokenList.isEmpty()) return;

        try {
            ArrayList<Long> tList = new ArrayList<>(tokenList);
            ticker.subscribe(tList);
            ticker.setMode(tList, KiteTicker.modeFull);
            
            // Correction: Ensure we know the symbol for these new tokens immediately
            for (Long t : tList) {
                String ts = String.valueOf(t);
                if (!tokenToSymbol.containsKey(ts)) {
                    // Try finding in master
                    try {
                        InstrumentService.InstrumentMeta meta = instrumentService.findByToken(ts);
                        if (meta != null && meta.symbol != null) {
                            tokenToSymbol.put(ts, meta.symbol);
                            tokenToInstrument.put(ts, meta.exchange + ":" + meta.symbol);
                            instrumentService.removeUnresolved(t); // It's resolved now
                        }
                    } catch (Exception e) {}
                }
            }
            log.info("Depth stream subscribed tokens count={}", tokenList.size());
        } catch (Exception e) {
            log.warn("Failed to subscribe tokens dynamically: {}", e.getMessage());
        }
    }

    private void handleTicks(List<Tick> ticks) {
        try {
            if (ticks == null) return;
            for (Tick tick : ticks) {
                long tokenVal = tick.getInstrumentToken();
                String tokenStr = String.valueOf(tokenVal);

                DepthView v = new DepthView();
                String sym = tokenToSymbol.getOrDefault(tokenStr, null);
                String instrument = tokenToInstrument.get(tokenStr);
                if (sym != null) v.setTradingsymbol(sym);
                
                try {
                    InstrumentService.InstrumentMeta meta = instrumentService.find(null, sym, tokenStr);
                    if (meta != null) {
                        if (meta.tickSize != null) v.setTick(meta.tickSize);
                        if (meta.lowerCircuit != null && v.getLowerCircuit() == null) v.setLowerCircuit(BigDecimal.valueOf(meta.lowerCircuit));
                        if (meta.upperCircuit != null && v.getUpperCircuit() == null) v.setUpperCircuit(BigDecimal.valueOf(meta.upperCircuit));
                    }
                } catch (Exception ignoredMeta) {}

                if (tick.getMarketDepth() != null) {
                    Map<String, ArrayList<com.zerodhatech.models.Depth>> depth = tick.getMarketDepth();
                    List<com.zerodhatech.models.Depth> buy = depth.get("buy");
                    List<com.zerodhatech.models.Depth> sell = depth.get("sell");
                    List<DepthView.Level> buyLvls = mapDepthLevels(buy);
                    List<DepthView.Level> sellLvls = mapDepthLevels(sell);
                    v.setBuyLevels(buyLvls);
                    v.setSellLevels(sellLvls);
                    v.setBuyQuantity(sumDepthQty(buy));
                    v.setSellQuantity(sumDepthQty(sell));
                    MaxTracker tracker = maxTrackerByToken.computeIfAbsent(tokenStr, k -> new MaxTracker());
                    tracker.updateFromTop(
                            !buyLvls.isEmpty() ? buyLvls.get(0) : null,
                            !sellLvls.isEmpty() ? sellLvls.get(0) : null);
                    MaxOrderInfo maxBid = tracker.getBid();
                    MaxOrderInfo maxAsk = tracker.getAsk();
                    if (maxBid != null) {
                        v.setMaxBuyOrderQty(maxBid.qty);
                        v.setMaxBuyOrderCount(maxBid.orders);
                        v.setMaxBuyOrderPrice(maxBid.price);
                    }
                    if (maxAsk != null) {
                        v.setMaxSellOrderQty(maxAsk.qty);
                        v.setMaxSellOrderCount(maxAsk.orders);
                        v.setMaxSellOrderPrice(maxAsk.price);
                    }
                }
                
                v.setLtp(BigDecimal.valueOf(tick.getLastTradedPrice()));
                if (tick.getLastTradedTime() != null) v.setLtt(tick.getLastTradedTime().toString());
                
                v.setVolume((long) tick.getVolumeTradedToday());
                v.setLtq((long) tick.getLastTradedQuantity());
                v.setOpen(BigDecimal.valueOf(tick.getOpenPrice()));
                v.setHigh(BigDecimal.valueOf(tick.getHighPrice()));
                v.setLow(BigDecimal.valueOf(tick.getLowPrice()));
                v.setPrevClose(BigDecimal.valueOf(tick.getClosePrice()));
                v.setAvgPrice(BigDecimal.valueOf(tick.getAverageTradePrice()));

                v.setCapturedAt(ZonedDateTime.now());
                recordHistory(tokenStr, v);
                applyHistoryMetrics(tokenStr, v);
                cacheByToken.put(tokenStr, v);
                

                // Coalescing Pattern: Offer to service, it persists asynchronously at 1/sec
                coalescingPersistenceService.offer(tokenVal, v);

                
                // Log to ticker log (Stream specific logging, optional but requested)
                // Note: DepthService.persistDepth also logs to ticker log if not suppressed.
                // If we want to avoid double logging, we should check what DepthService does.
                // DepthService.persistDepth logs Ticker Debug.
                // DepthStreamService previously logged Ticker Debug too.
                // We should rely on DepthService.persistDepth.
                // BUT DepthService.persistDepth suppresses if threshold met.
                // The requirements say: "Suppress logging AND persistence".
                // So if depthService suppresses, it won't log.
                // That matches the requirement.
            }
            if (log.isInfoEnabled() && !ticks.isEmpty()) {
                log.info("Processed market snapshots for {} tokens", ticks.size());
            }
        } catch (Exception e) {
            log.warn("Depth stream tick parse failed: {}", e.getMessage());
        }
    }

    private static class HistorySample { // ... (Same inner class)
        final long tsNanos; final double ltp; final long ltq; final Double bestBid; final Double bestAsk;
        HistorySample(long tsNanos, double ltp, long ltq, Double bestBid, Double bestAsk){this.tsNanos = tsNanos; this.ltp = ltp; this.ltq = ltq; this.bestBid = bestBid; this.bestAsk = bestAsk;}
    }

    private void recordHistory(String token, DepthView v) {
        try {
            if (v == null) return;
            double ltp = v.getLtp() != null ? v.getLtp().doubleValue() : Double.NaN;
            Double b1 = v.getBuyLevels() != null && !v.getBuyLevels().isEmpty() ? v.getBuyLevels().get(0).getPrice() : null;
            Double a1 = v.getSellLevels() != null && !v.getSellLevels().isEmpty() ? v.getSellLevels().get(0).getPrice() : null;
            long ltq = v.getLtq() != null ? v.getLtq() : 0L;
            long now = System.nanoTime();
            Deque<HistorySample> deque = historyByToken.computeIfAbsent(token, k -> new ArrayDeque<>());
            deque.addLast(new HistorySample(now, ltp, ltq, b1, a1));
            trimHistory(deque, now);
        } catch (Exception e) {}
    }

    private void trimHistory(Deque<HistorySample> deque, long now) {
        if (deque == null) return;
        long cutoff = now - resolveHistoryWindow().toNanos();
        while (!deque.isEmpty() && deque.getFirst().tsNanos < cutoff) {
            deque.removeFirst();
        }
    }

    private void applyHistoryMetrics(String token, DepthView v) {
        try {
            Deque<HistorySample> deque = historyByToken.get(token);
            if (deque == null || deque.size() < 2) return;
            HistorySample first = deque.getFirst();
            HistorySample last = deque.getLast();
            double dtSec = Math.max(1e-3, (last.tsNanos - first.tsNanos) / 1_000_000_000.0);
            double base = Double.isFinite(first.ltp) ? first.ltp : (first.bestBid != null && first.bestAsk != null ? (first.bestBid + first.bestAsk) / 2.0 : 0.0);
            double slope = Double.isFinite(last.ltp) && Double.isFinite(first.ltp) ? (last.ltp - first.ltp) / dtSec : 0.0;
            double driftBps = base > 0 ? (slope / base) * 10000.0 : 0.0;
            long ltqSum = deque.stream().mapToLong(h -> Math.max(0L, h.ltq)).sum();
            double ltqPerSec = ltqSum / dtSec;
            v.setDriftBps(round2(driftBps));
            v.setLtqPerSec(round2(ltqPerSec));
            Double L = v.getLowerCircuit() != null ? v.getLowerCircuit().doubleValue() : null;
            Double U = v.getUpperCircuit() != null ? v.getUpperCircuit().doubleValue() : null;
            Double tick = v.getTick();
            if (tick == null || tick <= 0) tick = inferTick(last.bestBid != null ? last.bestBid : (last.bestAsk != null ? last.bestAsk : last.ltp));
            if (Double.isFinite(last.ltp) && tick != null && tick > 0) {
                if (L != null) {
                    double dist = last.ltp - L;
                    if (dist <= 0) v.setTimeToBandSellSec(0.0);
                    else if (slope < 0) v.setTimeToBandSellSec(round2(dist / Math.abs(slope)));
                }
                if (U != null) {
                    double dist = U - last.ltp;
                    if (dist <= 0) v.setTimeToBandBuySec(0.0);
                    else if (slope > 0) v.setTimeToBandBuySec(round2(dist / Math.abs(slope)));
                }
            }
        } catch (Exception e) {}
    }

    private static double round2(double x){ return Math.round(x * 100.0) / 100.0; }

    private static double inferTick(Double refPrice) {
        if (refPrice == null || !(refPrice > 0)) return 0.05;
        if (refPrice < 250.0) return 0.01;
        if (refPrice > 1000.0) return 0.5;
        return 0.05;
    }

    private Duration resolveHistoryWindow() {
        int sec = settingsService.getInt("app.config.market.history-window-sec", 45);
        if (sec <= 0) sec = 45;
        return Duration.ofSeconds(sec);
    }

    private long sumDepthQty(List<com.zerodhatech.models.Depth> levels) {
        if (levels == null) return 0;
        long total = 0;
        for (com.zerodhatech.models.Depth d : levels) {
            total += d.getQuantity();
        }
        return total;
    }

    private static class MaxOrderInfo {
        final Long qty; final Integer orders; final Double price;
        MaxOrderInfo(Long qty, Integer orders, Double price) { this.qty = qty; this.orders = orders; this.price = price; }
    }

    private static class MaxTracker {
        private static class PriceState {
            long lastQty; int lastOrders; long maxDeltaQty; int maxDeltaOrders; final double price;
            PriceState(double price, long lastQty, int lastOrders) { this.price = price; this.lastQty = lastQty; this.lastOrders = lastOrders; this.maxDeltaQty = 0; this.maxDeltaOrders = 1; }
        }
        private final Map<Double, PriceState> bidStates = new HashMap<>();
        private final Map<Double, PriceState> askStates = new HashMap<>();

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

    private List<DepthView.Level> mapDepthLevels(List<com.zerodhatech.models.Depth> levels) {
        if (levels == null) return List.of();
        List<DepthView.Level> out = new ArrayList<>();
        int n = Math.min(5, levels.size());
        for (int i = 0; i < n; i++) {
            com.zerodhatech.models.Depth d = levels.get(i);
            out.add(new DepthView.Level(d.getQuantity(), d.getPrice(), d.getOrders()));
        }
        return out;
    }

    public List<DepthView> snapshotFor(UserAccount user) {
        List<Long> tokens = new ArrayList<>(UserAccountUtil.extractHoldingTokens(user));
        ZonedDateTime cutoff = ZonedDateTime.now().minusSeconds(10);
        return tokens.stream()
                .map(String::valueOf)
                .map(cacheByToken::get)
                .filter(Objects::nonNull)
                .filter(v -> v.getCapturedAt() != null && v.getCapturedAt().isAfter(cutoff))
                .filter(v -> v.getBuyLevels() != null && !v.getBuyLevels().isEmpty() && v.getSellLevels() != null && !v.getSellLevels().isEmpty())
                .collect(Collectors.toList());
    }

    public DepthView getSnapshot(String token) {
        return cacheByToken.get(token);
    }

    public boolean isDisabled() { return disabled; }
    public String getLastError() { return lastError; }


}
