package com.exittrading.app.controller;

import com.exittrading.app.service.IstClock;
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

    @Value("${kite.apiKey}")
    private String apiKey;

    @Value("${kite.apiSecret}")
    private String apiSecret;

    public SessionController(KiteSessionManager sessionManager, IstClock clock) {
        this.sessionManager = sessionManager;
        this.clock = clock;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> payload) {
        try {
            String requestToken = payload.get("requestToken");
            Class<?> kiteClass = Class.forName("com.zerodhatech.kiteconnect.KiteConnect");
            Object kite = kiteClass.getConstructor(String.class).newInstance(apiKey);
            Method generateSession = kiteClass.getMethod("generateSession", String.class, String.class);
            Object user = generateSession.invoke(kite, requestToken, apiSecret);
            invokeIfPresent(kiteClass, kite, "setAccessToken", extractField(user, "accessToken"));
            invokeIfPresent(kiteClass, kite, "setPublicToken", extractField(user, "publicToken"));
            attachExpiryHook(kiteClass, kite);
            String userName = extractField(user, "userName");
            ZonedDateTime expiry = extractExpiry(user, "accessTokenExpiry");
            sessionManager.initializeSession(kite, userName, expiry);
            return ResponseEntity.ok(Map.of("status", "connected", "user", userName, "expiry", expiry));
        } catch (ClassNotFoundException ex) {
            log.error("KiteConnect jar not found", ex);
            return ResponseEntity.status(500).body(Map.of(
                    "status", "error",
                    "message", "KiteConnect library not available on classpath. Upload kiteconnect.jar to lib/ and restart."
            ));
        } catch (Exception ex) {
            log.error("Kite login failed", ex);
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", ex.getMessage()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        sessionManager.invalidateSession();
        return ResponseEntity.ok(Map.of("status", "disconnected"));
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
}
