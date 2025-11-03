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

    private final KiteSessionManager sessionManager;

    private Object ticker; // com.zerodhatech.ticker.KiteTicker
    private final Map<String, DepthView> cacheByToken = new ConcurrentHashMap<>();
    private final Map<String, String> tokenToSymbol = new ConcurrentHashMap<>();
    private volatile boolean disabled = false; // disable streaming after fatal handshake errors
    private volatile String lastError = null;

    public DepthStreamService(KiteSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    public synchronized void startForUser(UserAccount user) {
        try {
            if (ticker != null) return;
            if (disabled) {
                log.warn("Depth stream disabled due to previous handshake error; using REST fallbacks");
                return;
            }
            Object kite = sessionManager.getKiteConnect();
            String accessToken = invokeString(kite, "getAccessToken");
            String apiKey = invokeString(kite, "getApiKey");
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
                    log.warn("Depth stream handshake rejected (403). Disabling streaming and falling back to REST.");
                    ticker = null;
                    return;
                }
                throw rte;
            }
            log.info("Depth stream connected (requested)");
        } catch (Exception e) {
            lastError = e.getMessage();
            log.warn("Unable to start depth stream: {}", e.getMessage());
        }
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

                Map<String, List<?>> md = (Map<String, List<?>>) invokeMaybe(tClass, tick, "getMarketDepth");
                if (md != null) {
                    List<?> buy = md.get("buy");
                    List<?> sell = md.get("sell");
                    v.setBuyLevels(mapDepthLevels(buy));
                    v.setSellLevels(mapDepthLevels(sell));
                    v.setBuyQuantity(sumDepthQty(buy));
                    v.setSellQuantity(sumDepthQty(sell));
                }
                Number ltp = (Number) invokeMaybe(tClass, tick, "getLastTradedPrice");
                if (ltp != null) v.setLtp(BigDecimal.valueOf(ltp.doubleValue()));
                Object ltt = invokeMaybe(tClass, tick, "getLastTradedTime");
                if (ltt != null) v.setLtt(String.valueOf(ltt));
                v.setCapturedAt(ZonedDateTime.now());
                cacheByToken.put(tokenStr, v);
            }
        } catch (Exception e) {
            log.warn("Depth stream tick parse failed: {}", e.getMessage());
        }
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
        return tokens.stream()
                .map(String::valueOf)
                .map(cacheByToken::get)
                .filter(Objects::nonNull)
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
}
