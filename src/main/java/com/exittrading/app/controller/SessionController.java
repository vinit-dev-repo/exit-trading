package com.exittrading.app.controller;

import com.exittrading.app.service.IstClock;
import com.exittrading.app.service.AdminService;
import org.springframework.beans.factory.annotation.Autowired;
import com.exittrading.app.service.KiteSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/session")
public class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    private final KiteSessionManager sessionManager;
    private final IstClock clock;
    private final AdminService adminService;

    @Value("${kite.apiKey}")
    private String apiKey;

    @Value("${kite.apiSecret}")
    private String apiSecret;

    @Value("${kite.default.exchange:NSE}")
    private String defaultExchange;

    @Autowired
    public SessionController(KiteSessionManager sessionManager, IstClock clock, AdminService adminService) {
        this.sessionManager = sessionManager;
        this.clock = clock;
        this.adminService = adminService;
    }

    // Backward-compatible constructor for existing tests
    public SessionController(KiteSessionManager sessionManager, IstClock clock) {
        this(sessionManager, clock, null);
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> payload) {
        try {
            String requestToken = payload.get("requestToken");
            LoginResult res = performLogin(requestToken);
            return ResponseEntity.ok(Map.of("status", "connected", "user", res.userName, "expiry", res.expiry));
        } catch (ClassNotFoundException ex) {
            log.error("KiteConnect jar not found", ex);
            return ResponseEntity.status(500).body(Map.of(
                    "status", "error",
                    "message", "KiteConnect library not available on classpath. Upload kiteconnect.jar to lib/ and restart."
            ));
        } catch (Exception ex) {
            log.error("Kite login failed", ex);
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            String msg = (cause.getClass().getSimpleName() + (cause.getMessage() != null ? (": " + cause.getMessage()) : ""));
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", msg));
        }
    }

    // Browser callback endpoint for Kite OAuth redirect
    // Configure this exact URL in Kite developer console as the Redirect URL, e.g. http://localhost:8080/api/admin/session/callback
    @GetMapping("/callback")
    public ResponseEntity<?> callback(@RequestParam(name = "request_token", required = false) String requestToken,
                                      @RequestParam(name = "status", required = false) String status) {
        try {
            if (requestToken == null || (status != null && !"success".equalsIgnoreCase(status))) {
                // redirect to dashboard with error
                return ResponseEntity.status(302).header("Location", "/?kite=error").build();
            }
            performLogin(requestToken);
            return ResponseEntity.status(302).header("Location", "/?kite=connected").build();
        } catch (ClassNotFoundException ex) {
            log.error("KiteConnect jar not found", ex);
            return ResponseEntity.status(302).header("Location", "/?kite=missingjar").build();
        } catch (Exception ex) {
            log.error("Kite login failed via callback", ex);
            return ResponseEntity.status(302).header("Location", "/?kite=failed").build();
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        sessionManager.invalidateSession();
        return ResponseEntity.ok(Map.of("status", "disconnected"));
    }

    // Manual holdings sync for current Kite session user
    @PostMapping("/holdings/sync")
    public ResponseEntity<?> syncHoldings() {
        try {
            Object kite = sessionManager.getKiteConnect();
            String user = sessionManager.getUserName();
            int count = syncHoldingsFromKite(kite, user);
            return ResponseEntity.ok(Map.of("status", "ok", "updated", count));
        } catch (Exception ex) {
            log.error("Holdings sync failed", ex);
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            String msg = (cause.getClass().getSimpleName() + (cause.getMessage() != null ? (": " + cause.getMessage()) : ""));
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", msg));
        }
    }

    private String extractField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getField(fieldName);
            field.setAccessible(true);
            Object value = field.get(target);
            return value != null ? value.toString() : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private String extractAnyField(Object target, String... fieldNames) {
        for (String name : fieldNames) {
            String v = extractField(target, name);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private Number extractNumber(Object target, String... fieldNames) {
        for (String name : fieldNames) {
            try {
                Field f = target.getClass().getField(name);
                f.setAccessible(true);
                Object v = f.get(target);
                if (v instanceof Number n) return n;
                if (v != null) {
                    try { return new java.math.BigDecimal(v.toString()); } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private ZonedDateTime extractExpiry(Object target, String fieldName) {
        try {
            Field field = target.getClass().getField(fieldName);
            field.setAccessible(true);
            Object value = field.get(target);
            if (value instanceof Date date) {
                return ZonedDateTime.ofInstant(date.toInstant(), clock.zoneId());
            }
            if (value instanceof Instant instant) {
                return clock.fromInstant(instant);
            }
        } catch (Exception ignored) {
        }
        return clock.now().plusHours(6);
    }

    private void invokeIfPresent(Class<?> targetType, Object target, String methodName, Object argument) {
        if (argument == null) {
            return;
        }
        try {
            Method method = targetType.getMethod(methodName, String.class);
            method.invoke(target, argument.toString());
        } catch (Exception ignored) {
        }
    }

    private void attachExpiryHook(Class<?> kiteClass, Object kite) {
        try {
            Class<?> hookClass = Class.forName("com.zerodhatech.kiteconnect.kitehttp.SessionExpiryHook");
            Object hook = java.lang.reflect.Proxy.newProxyInstance(hookClass.getClassLoader(), new Class<?>[]{hookClass},
                    (proxy, method, args) -> {
                        sessionManager.invalidateSession();
                        return null;
                    });
            Method setHook = kiteClass.getMethod("setSessionExpiryHook", hookClass);
            setHook.invoke(kite, hook);
        } catch (Exception ignored) {
        }
    }

    private LoginResult performLogin(String requestToken) throws Exception {
        Class<?> kiteClass = Class.forName("com.zerodhatech.kiteconnect.KiteConnect");
        Object kite = kiteClass.getConstructor(String.class).newInstance(apiKey);
        Method generateSession = kiteClass.getMethod("generateSession", String.class, String.class);
        Object user = generateSession.invoke(kite, requestToken, apiSecret);
        invokeIfPresent(kiteClass, kite, "setAccessToken", extractField(user, "accessToken"));
        invokeIfPresent(kiteClass, kite, "setPublicToken", extractField(user, "publicToken"));
        attachExpiryHook(kiteClass, kite);
        String userName = extractField(user, "userName");
        String userId = extractField(user, "userId");
        ZonedDateTime expiry = extractExpiry(user, "accessTokenExpiry");
        sessionManager.initializeSession(kite, userName, expiry);
        if (adminService != null) {
            adminService.ensureUserExists(userName, userName);
            // Best-effort holdings sync so UI shows actual holdings for this user
            try { syncHoldingsFromKite(kite, userName); } catch (Exception ignored) { }
        }
        return new LoginResult(userName, expiry);
    }

    private record LoginResult(String userName, ZonedDateTime expiry) {}

    @SuppressWarnings("unchecked")
    private int syncHoldingsFromKite(Object kite, String userName) throws Exception {
        Method getHoldings = kite.getClass().getMethod("getHoldings");
        Object result = getHoldings.invoke(kite);
        java.util.Set<String> symbols = new java.util.HashSet<>();
        if (result instanceof java.util.List<?> list) {
            for (Object item : list) {
                // Kite Holding field is public String tradingSymbol (SerializedName "tradingsymbol")
                String symbol = extractAnyField(item, "tradingSymbol", "tradingsymbol");
                String exch = extractAnyField(item, "exchange");
                Number qty = extractNumber(item, "quantity", "qty");
                Number avg = extractNumber(item, "averagePrice", "average_price");
                Number last = extractNumber(item, "lastPrice", "last_price");
                Number pnl = extractNumber(item, "pnl");
                Number pct = extractNumber(item, "dayChangePercentage", "day_change_percentage");
                String product = extractAnyField(item, "product");
                String token = extractAnyField(item, "instrumentToken", "instrument_token");
                if (symbol != null) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(exch != null && !exch.isBlank() ? exch.toUpperCase() + ":" + symbol : symbol);
                    if (qty != null || avg != null) {
                        sb.append('|');
                        sb.append(qty != null ? qty.toString() : "");
                        sb.append('|');
                        sb.append(avg != null ? new java.math.BigDecimal(avg.toString()).setScale(2, java.math.RoundingMode.HALF_UP) : "");
                        // optional additional fields: last|pnl|pct|product|token
                        sb.append('|');
                        sb.append(last != null ? new java.math.BigDecimal(last.toString()).setScale(2, java.math.RoundingMode.HALF_UP) : "");
                        sb.append('|');
                        sb.append(pnl != null ? new java.math.BigDecimal(pnl.toString()).setScale(2, java.math.RoundingMode.HALF_UP) : "");
                        sb.append('|');
                        sb.append(pct != null ? new java.math.BigDecimal(pct.toString()).setScale(2, java.math.RoundingMode.HALF_UP) : "");
                        sb.append('|');
                        sb.append(product != null ? product : "");
                        sb.append('|');
                        sb.append(token != null ? token : "");
                    }
                    symbols.add(sb.toString());
                }
            }
        } else if (result instanceof java.util.Map<?,?> map) {
            for (Object val : map.values()) {
                String symbol = extractAnyField(val, "tradingSymbol", "tradingsymbol");
                String exch = extractAnyField(val, "exchange");
                Number qty = extractNumber(val, "quantity", "qty");
                Number avg = extractNumber(val, "averagePrice", "average_price");
                Number last = extractNumber(val, "lastPrice", "last_price");
                Number pnl = extractNumber(val, "pnl");
                Number pct = extractNumber(val, "dayChangePercentage", "day_change_percentage");
                String product = extractAnyField(val, "product");
                String token = extractAnyField(val, "instrumentToken", "instrument_token");
                if (symbol != null) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(exch != null && !exch.isBlank() ? exch.toUpperCase() + ":" + symbol : symbol);
                    if (qty != null || avg != null) {
                        sb.append('|');
                        sb.append(qty != null ? qty.toString() : "");
                        sb.append('|');
                        sb.append(avg != null ? new java.math.BigDecimal(avg.toString()).setScale(2, java.math.RoundingMode.HALF_UP) : "");
                        sb.append('|');
                        sb.append(last != null ? new java.math.BigDecimal(last.toString()).setScale(2, java.math.RoundingMode.HALF_UP) : "");
                        sb.append('|');
                        sb.append(pnl != null ? new java.math.BigDecimal(pnl.toString()).setScale(2, java.math.RoundingMode.HALF_UP) : "");
                        sb.append('|');
                        sb.append(pct != null ? new java.math.BigDecimal(pct.toString()).setScale(2, java.math.RoundingMode.HALF_UP) : "");
                        sb.append('|');
                        sb.append(product != null ? product : "");
                        sb.append('|');
                        sb.append(token != null ? token : "");
                    }
                    symbols.add(sb.toString());
                }
            }
        }
        adminService.updateHoldings(userName, symbols);
        return symbols.size();
    }
}
