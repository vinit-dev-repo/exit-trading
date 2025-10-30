package com.exittrading.app.controller;

import com.exittrading.app.service.AdminService;
import com.exittrading.app.service.IstClock;
import com.exittrading.app.service.KiteSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Date;

@RestController
@RequestMapping("/external")
public class KiteRedirectController {

    private static final Logger log = LoggerFactory.getLogger(KiteRedirectController.class);

    private final KiteSessionManager sessionManager;
    private final IstClock clock;
    private final AdminService adminService;

    @Value("${kite.apiKey}")
    private String apiKey;

    @Value("${kite.apiSecret}")
    private String apiSecret;

    public KiteRedirectController(KiteSessionManager sessionManager, IstClock clock, AdminService adminService) {
        this.sessionManager = sessionManager;
        this.clock = clock;
        this.adminService = adminService;
    }

    // Supports existing redirect pattern: /external/kiteredirect?status=success&request_token=...
    @GetMapping("/kiteredirect")
    public ResponseEntity<?> kiteRedirect(@RequestParam(name = "request_token", required = false) String requestToken,
                                          @RequestParam(name = "status", required = false) String status) {
        try {
            if (requestToken == null || (status != null && !"success".equalsIgnoreCase(status))) {
                return ResponseEntity.status(302).header("Location", "/?kite=error").build();
            }
            performLogin(requestToken);
            return ResponseEntity.status(302).header("Location", "/?kite=connected").build();
        } catch (ClassNotFoundException ex) {
            log.error("KiteConnect jar not found", ex);
            return ResponseEntity.status(302).header("Location", "/?kite=missingjar").build();
        } catch (Exception ex) {
            log.error("Kite login failed via /external/kiteredirect", ex);
            return ResponseEntity.status(302).header("Location", "/?kite=failed").build();
        }
    }

    private void performLogin(String requestToken) throws Exception {
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
        if (adminService != null) {
            adminService.ensureUserExists(userName, userName);
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

