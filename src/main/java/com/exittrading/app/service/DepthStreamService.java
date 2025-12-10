package com.exittrading.app.service;

import com.exittrading.app.domain.UserAccount;
import com.exittrading.app.dto.DepthView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.*;
import java.time.ZonedDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class DepthStreamService {

    private static final Logger log = LoggerFactory.getLogger(DepthStreamService.class);
    private static final Logger tlog = LoggerFactory.getLogger("ticker");

    private final KiteSessionManager sessionManager;
    private final InstrumentService instrumentService;

    private Object ticker; // com.zerodhatech.ticker.KiteTicker
    private final Map<String, DepthView> cacheByToken = new ConcurrentHashMap<>();
    private final Map<String, String> tokenToSymbol = new ConcurrentHashMap<>();
    private final Map<String, java.util.Deque<HistorySample>> historyByToken = new ConcurrentHashMap<>();
    private final Map<String, MaxTracker> maxTrackerByToken = new ConcurrentHashMap<>();
    private static final java.time.Duration HISTORY_WINDOW = java.time.Duration.ofSeconds(45);
    private volatile boolean disabled = false; // disable streaming after fatal handshake errors
    private volatile String lastError = null;
    private volatile String lastAccessToken = null;
    private final java.util.concurrent.ScheduledExecutorService retryScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r);
        t.setName("depth-retry-" + t.getId());
        t.setUncaughtExceptionHandler((thr, ex) -> log.error("Uncaught exception in {}", thr.getName(), ex));
        return t;
    });

    public DepthStreamService(KiteSessionManager sessionManager, InstrumentService instrumentService) {
        this.sessionManager = sessionManager;
        this.instrumentService = instrumentService;
    }

    public synchronized void startForUser(UserAccount user) {
        try {
            if (ticker != null) return;
            if (disabled) {
                try {
                    Object kite = sessionManager.getKiteConnect();
                    String currentToken = invokeString(kite, "getAccessToken");
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
            Object kite = sessionManager.getKiteConnect();
            String accessToken = invokeString(kite, "getAccessToken");
            String apiKey = invokeString(kite, "getApiKey");
            lastAccessToken = accessToken;
            Class<?> tickerClass = Class.forName("com.zerodhatech.ticker.KiteTicker");
            ticker = tickerClass.getConstructor(String.class, String.class).newInstance(accessToken, apiKey);

            // Reconnection settings
            invokeIfPresent(tickerClass, ticker, "setTryReconnection", true);
            invokeIfPresent(tickerClass, ticker, "setMaximumRetries", 10);
            invokeIfPresent(tickerClass, ticker, "setMaximumRetryInterval", 30);

            // OnTicks listener via dynamic proxy (official name: setOnTicks)
            try {
                Class<?> onTicksClass = Class.forName("com.zerodhatech.ticker.OnTicks");
                Object onTicks = Proxy.newProxyInstance(onTicksClass.getClassLoader(), new Class[]{onTicksClass}, (proxy, method, args) -> {
                    if ("onTicks".equals(method.getName()) && args != null && args.length == 1) {
                        handleTicks(args[0]);
                    }
                    return null;
                });
                // Try both method names: setOnTicks and setOnTickerArrivalListener
                if (!invokeIfPresent(tickerClass, ticker, "setOnTicks", onTicks)) {
                    invokeIfPresent(tickerClass, ticker, "setOnTickerArrivalListener", onTicks);
                }
            } catch (ClassNotFoundException ignored) {}

            // OnConnect listener subscribes to holdings tokens (method name: setOnConnect or setOnConnectedListener)
            try {
                Class<?> onConnectClass = Class.forName("com.zerodhatech.ticker.OnConnect");
                Object onConnect = Proxy.newProxyInstance(onConnectClass.getClassLoader(), new Class[]{onConnectClass}, (proxy, method, args) -> {
                    if ("onConnected".equals(method.getName())) {
                        try { subscribeHoldings(user); } catch (Exception e) { log.warn("Depth stream subscribe failed: {}", e.getMessage()); }
                    }
                    return null;
                });
                if (!invokeIfPresent(tickerClass, ticker, "setOnConnect", onConnect)) {
                    invokeIfPresent(tickerClass, ticker, "setOnConnectedListener", onConnect);
                }
            } catch (ClassNotFoundException ignored) {}

            // Optional handlers for errors/close/reconnect
            try {
                Class<?> onErrorClass = Class.forName("com.zerodhatech.ticker.OnError");
                Object onError = Proxy.newProxyInstance(onErrorClass.getClassLoader(), new Class[]{onErrorClass}, (proxy, method, args) -> {
                    log.warn("Depth stream error via {}: {}", method.getName(), (args != null && args.length > 0 && args[0] != null) ? args[0].toString() : "-");
                    return null;
                });
                if (!invokeIfPresent(tickerClass, ticker, "setOnError", onError)) {
                    invokeIfPresent(tickerClass, ticker, "setOnErrorListener", onError);
                }
            } catch (ClassNotFoundException ignored) {}
            try {
                Class<?> onCloseClass = Class.forName("com.zerodhatech.ticker.OnClose");
                Object onClose = Proxy.newProxyInstance(onCloseClass.getClassLoader(), new Class[]{onCloseClass}, (proxy, method, args) -> { log.info("Depth stream closed"); return null; });
                invokeIfPresent(tickerClass, ticker, "setOnClose", onClose);
            } catch (ClassNotFoundException ignored) {}
            try {
                Class<?> onReconnectClass = Class.forName("com.zerodhatech.ticker.OnReconnect");
                Object onReconnect = Proxy.newProxyInstance(onReconnectClass.getClassLoader(), new Class[]{onReconnectClass}, (proxy, method, args) -> { log.info("Depth stream reconnect {}", args != null && args.length > 0 ? args[0] : ""); return null; });
                invokeIfPresent(tickerClass, ticker, "setOnReconnect", onReconnect);
            } catch (ClassNotFoundException ignored) {}
            try {
                Class<?> onNoReconnectClass = Class.forName("com.zerodhatech.ticker.OnNoReconnect");
                Object onNoRe = Proxy.newProxyInstance(onNoReconnectClass.getClassLoader(), new Class[]{onNoReconnectClass}, (proxy, method, args) -> { log.warn("Depth stream no-reconnect"); return null; });
                invokeIfPresent(tickerClass, ticker, "setOnNoReconnect", onNoRe);
            } catch (ClassNotFoundException ignored) {}

            // Connect
            try {
                // Call connect via reflection and allow exceptions to propagate so we can inspect
                Method connect = tickerClass.getMethod("connect");
                connect.invoke(ticker);
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
        } catch (Exception e) {
            lastError = e.getMessage();
            log.warn("Unable to start depth stream: {}", e.getMessage());
            scheduleRetry();
        }
    }

    private void scheduleRetry() {
        try {
            retryScheduler.schedule(() -> {
                try {
                    if (!disabled) return; // already re-enabled
                    log.info("Retrying depth stream connection after backoff");
                    // Best-effort: pick any user from token map as a hint; otherwise requires caller to re-invoke startForUser
                    // We don’t have the user here; rely on external calls to startForUser after login/refresh
                    // This retry simply clears the disabled flag to allow next start attempt
                    disabled = false;
                } catch (Exception ignore) {}
            }, 30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {}
    }

    private void subscribeHoldings(UserAccount user) throws Exception {
        List<Long> tokens = extractTokens(user);
        if (tokens.isEmpty()) return;
        for (String h : user.getHoldings()) {
            String[] parts = h.split("\\|");
            if (parts.length >= 8 && parts[7] != null && !parts[7].isBlank()) {
                String token = parts[7].trim();
                String sym = parts[0].contains(":") ? parts[0].substring(parts[0].indexOf(':') + 1) : parts[0];
                tokenToSymbol.put(token, sym);
            }
        }
        Class<?> tickerClass = ticker.getClass();
        Method subscribe;
        try {
            subscribe = tickerClass.getMethod("subscribe", ArrayList.class);
            subscribe.invoke(ticker, new ArrayList<>(tokens));
        } catch (NoSuchMethodException e) {
            subscribe = tickerClass.getMethod("subscribe", List.class);
            subscribe.invoke(ticker, tokens);
        }
        // set mode full
        int modeFull;
        try { modeFull = (int) Class.forName("com.zerodhatech.ticker.KiteTicker").getField("modeFull").get(null); }
        catch (NoSuchFieldException nf) { modeFull = (int) Class.forName("com.zerodhatech.ticker.KiteTicker").getField("modeFULL").get(null); }
        try {
            Method setMode = tickerClass.getMethod("setMode", ArrayList.class, int.class);
            setMode.invoke(ticker, new ArrayList<>(tokens), modeFull);
        } catch (NoSuchMethodException e) {
            Method setMode = tickerClass.getMethod("setMode", List.class, int.class);
            setMode.invoke(ticker, tokens, modeFull);
        }
        log.info("Depth stream subscribed tokens count={}", tokens.size());
    }

    private List<Long> extractTokens(UserAccount user) {
        if (user == null || user.getHoldings() == null) return List.of();
        return user.getHoldings().stream()
                .map(s -> s.split("\\|")).filter(a -> a.length >= 8 && a[7] != null && !a[7].isBlank())
                .map(a -> a[7].trim())
                .filter(t -> t.chars().allMatch(Character::isDigit))
                .distinct()
                .map(Long::valueOf)
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private void handleTicks(Object ticksArg) {
        try {
            if (!(ticksArg instanceof List<?> ticks)) return;
            for (Object tick : ticks) {
                Class<?> tClass = tick.getClass();
                Number token = (Number) invokeMaybe(tClass, tick, "getInstrumentToken");
                if (token == null) token = (Number) invokeMaybe(tClass, tick, "getToken");
                String tokenStr = token != null ? String.valueOf(token.longValue()) : null;
                if (tokenStr == null) continue;

                DepthView v = new DepthView();
                String sym = tokenToSymbol.getOrDefault(tokenStr, null);
                if (sym != null) v.setTradingsymbol(sym);
                // Enrich tick/circuit metadata from instruments master
                try {
                    InstrumentService.InstrumentMeta meta = instrumentService.find(null, sym, tokenStr);
                    if (meta != null) {
                        if (meta.tickSize != null) v.setTick(meta.tickSize);
                        if (meta.lowerCircuit != null && v.getLowerCircuit() == null) v.setLowerCircuit(BigDecimal.valueOf(meta.lowerCircuit));
                        if (meta.upperCircuit != null && v.getUpperCircuit() == null) v.setUpperCircuit(BigDecimal.valueOf(meta.upperCircuit));
                    }
                } catch (Exception ignoredMeta) {}

                Map<String, List<?>> md = (Map<String, List<?>>) invokeMaybe(tClass, tick, "getMarketDepth");
                if (md != null) {
                    List<?> buy = md.get("buy");
                    List<?> sell = md.get("sell");
                    List<DepthView.Level> buyLvls = mapDepthLevels(buy);
                    List<DepthView.Level> sellLvls = mapDepthLevels(sell);
                    v.setBuyLevels(buyLvls);
                    v.setSellLevels(sellLvls);
                    v.setBuyQuantity(sumDepthQty(buy));
                    v.setSellQuantity(sumDepthQty(sell));
                    MaxTracker tracker = maxTrackerByToken.computeIfAbsent(tokenStr, k -> new MaxTracker());
                    tracker.updateFromTop(
                            buyLvls != null && !buyLvls.isEmpty() ? buyLvls.get(0) : null,
                            sellLvls != null && !sellLvls.isEmpty() ? sellLvls.get(0) : null);
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
                Number ltp = (Number) invokeMaybe(tClass, tick, "getLastTradedPrice");
                if (ltp != null) v.setLtp(BigDecimal.valueOf(ltp.doubleValue()));
                Object ltt = invokeMaybe(tClass, tick, "getLastTradedTime");
                if (ltt != null) v.setLtt(String.valueOf(ltt));
                v.setCapturedAt(ZonedDateTime.now());
                recordHistory(tokenStr, v);
                applyHistoryMetrics(tokenStr, v);
                cacheByToken.put(tokenStr, v);

                // Log a concise, readable per-holding line to ticker log
                if (sym != null) {
                    MaxTracker tracker = maxTrackerByToken.get(tokenStr);
                    MaxOrderInfo mb = tracker != null ? tracker.getBid() : null;
                    MaxOrderInfo ma = tracker != null ? tracker.getAsk() : null;
                    String bestBid = formatLevelSafe(v.getBuyLevels());
                    String bestAsk = formatLevelSafe(v.getSellLevels());
                    String ltpStr = v.getLtp() != null ? v.getLtp().toPlainString() : "-";
                    String ts = v.getCapturedAt() != null ? v.getCapturedAt().toLocalTime().toString() : "-";
                    String maxStr = String.format(" | maxBid=%s | maxAsk=%s",
                            formatMax(mb), formatMax(ma));
                    tlog.info("{} | ts={} | ltp={} | buyQty={} | sellQty={} | bid1={} | ask1={}{}",
                            sym,
                            ts,
                            ltpStr,
                            v.getBuyQuantity(),
                            v.getSellQuantity(),
                            bestBid,
                            bestAsk,
                            maxStr);
                }
            }
        } catch (Exception e) {
            log.warn("Depth stream tick parse failed: {}", e.getMessage());
        }
    }

    private static class HistorySample {
        final long tsNanos;
        final double ltp;
        final long ltq;
        final Double bestBid;
        final Double bestAsk;
        HistorySample(long tsNanos, double ltp, long ltq, Double bestBid, Double bestAsk){
            this.tsNanos = tsNanos; this.ltp = ltp; this.ltq = ltq; this.bestBid = bestBid; this.bestAsk = bestAsk;
        }
    }

    private void recordHistory(String token, DepthView v) {
        try {
            if (v == null) return;
            double ltp = v.getLtp() != null ? v.getLtp().doubleValue() : Double.NaN;
            Double b1 = v.getBuyLevels() != null && !v.getBuyLevels().isEmpty() ? v.getBuyLevels().get(0).getPrice() : null;
            Double a1 = v.getSellLevels() != null && !v.getSellLevels().isEmpty() ? v.getSellLevels().get(0).getPrice() : null;
            long ltq = v.getLtq() != null ? v.getLtq() : 0L;
            long now = System.nanoTime();
            java.util.Deque<HistorySample> deque = historyByToken.computeIfAbsent(token, k -> new java.util.ArrayDeque<>());
            deque.addLast(new HistorySample(now, ltp, ltq, b1, a1));
            trimHistory(deque, now);
        } catch (Exception ignored) {}
    }

    private void trimHistory(java.util.Deque<HistorySample> deque, long now) {
        if (deque == null) return;
        long cutoff = now - HISTORY_WINDOW.toNanos();
        while (!deque.isEmpty() && deque.getFirst().tsNanos < cutoff) {
            deque.removeFirst();
        }
    }

    private void applyHistoryMetrics(String token, DepthView v) {
        try {
            java.util.Deque<HistorySample> deque = historyByToken.get(token);
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
        } catch (Exception ignored) {}
    }

    private String formatLevelSafe(java.util.List<DepthView.Level> levels){
        if (levels == null || levels.isEmpty()) return "-";
        DepthView.Level l = levels.get(0);
        String p = String.valueOf(l.getPrice());
        String q = String.valueOf(l.getQuantity());
        String o = String.valueOf(l.getOrders());
        return p + "×" + q + "(" + o + ")";
    }

    private static double round2(double x){ return Math.round(x * 100.0) / 100.0; }

    private static double inferTick(Double refPrice) {
        if (refPrice == null || !(refPrice > 0)) return 0.05;
        if (refPrice < 250.0) return 0.01;
        if (refPrice > 1000.0) return 0.5;
        return 0.05;
    }

    private long sumDepthQty(List<?> levels) {
        if (levels == null) return 0;
        long total = 0;
        for (Object lv : levels) {
            Number q = (Number) invokeMaybe(lv.getClass(), lv, "getQuantity");
            if (q != null) total += q.longValue();
        }
        return total;
    }

    private static class MaxOrderInfo {
        final Long qty;
        final Integer orders;
        final Double price;
        MaxOrderInfo(Long qty, Integer orders, Double price) { this.qty = qty; this.orders = orders; this.price = price; }
    }
    /**
     * Tracks per-price deltas of top-of-book and keeps the largest positive delta ever seen (sticky).
     * Never resets during process lifetime.
     */
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
        private final java.util.Map<Double, PriceState> bidStates = new java.util.HashMap<>();
        private final java.util.Map<Double, PriceState> askStates = new java.util.HashMap<>();

        synchronized void updateFromTop(DepthView.Level topBid, DepthView.Level topAsk){
            if (topBid != null) updateSide(bidStates, topBid);
            if (topAsk != null) updateSide(askStates, topAsk);
        }

        private void updateSide(java.util.Map<Double, PriceState> map, DepthView.Level lvl){
            double px = lvl.getPrice();
            long qty = lvl.getQuantity();
            int orders = lvl.getOrders();
            PriceState st = map.get(px);
            if (st == null) {
                // First sighting: establish baseline, no delta yet
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

        private MaxOrderInfo best(java.util.Map<Double, PriceState> map){
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

    private MaxOrderInfo maxOrderInfo(List<DepthView.Level> levels) {
        if (levels == null || levels.isEmpty()) return null;
        DepthView.Level best = null;
        for (DepthView.Level lv : levels) {
            if (lv == null || lv.getQuantity() <= 0) continue;
            if (best == null || lv.getQuantity() > best.getQuantity()) {
                best = lv;
            }
        }
        if (best == null) return null;
        return new MaxOrderInfo((long) best.getQuantity(), best.getOrders(), best.getPrice());
    }

    private List<DepthView.Level> mapDepthLevels(List<?> levels) {
        if (levels == null) return List.of();
        List<DepthView.Level> out = new ArrayList<>();
        int n = Math.min(5, levels.size());
        for (int i = 0; i < n; i++) {
            Object lv = levels.get(i);
            Number q = (Number) invokeMaybe(lv.getClass(), lv, "getQuantity");
            Number p = (Number) invokeMaybe(lv.getClass(), lv, "getPrice");
            Number o = (Number) invokeMaybe(lv.getClass(), lv, "getOrders");
            out.add(new DepthView.Level(q != null ? q.intValue() : 0, p != null ? p.doubleValue() : 0.0, o != null ? o.intValue() : 0));
        }
        return out;
    }

    public List<DepthView> snapshotFor(UserAccount user) {
        List<Long> tokens = extractTokens(user);
        ZonedDateTime cutoff = ZonedDateTime.now().minusSeconds(10);
        return tokens.stream()
                .map(String::valueOf)
                .map(cacheByToken::get)
                .filter(Objects::nonNull)
                .filter(v -> v.getCapturedAt() != null && v.getCapturedAt().isAfter(cutoff))
                .filter(v -> v.getBuyLevels() != null && !v.getBuyLevels().isEmpty() && v.getSellLevels() != null && !v.getSellLevels().isEmpty())
                .collect(Collectors.toList());
    }

    public boolean isDisabled() { return disabled; }
    public String getLastError() { return lastError; }

    private static String invokeString(Object target, String method) throws Exception {
        Method m = target.getClass().getMethod(method);
        Object v = m.invoke(target);
        return v != null ? v.toString() : null;
    }

    private static Object invokeMaybe(Class<?> type, Object target, String method) {
        try {
            Method m = type.getMethod(method);
            return m.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean invokeIfPresent(Class<?> type, Object target, String method, Object... args) {
        try {
            Class<?>[] types = Arrays.stream(args).map(a -> {
                if (a instanceof Boolean) return boolean.class;
                if (a instanceof Integer) return int.class;
                if (a instanceof Long) return long.class;
                return a.getClass();
            }).toArray(Class[]::new);
            Method m = type.getMethod(method, types);
            m.invoke(target, args);
            return true;
        } catch (Exception ignored) { return false; }
    }

    private static String formatMax(MaxOrderInfo m){
        if (m == null || m.qty == null) return "-";
        String qty = String.valueOf(m.qty);
        String ord = (m.orders != null && m.orders > 0) ? String.valueOf(m.orders) : "1";
        String px = (m.price != null) ? String.valueOf(m.price) : "?";
        return qty + " per " + ord + " @" + px;
    }
}
