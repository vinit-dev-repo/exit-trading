package com.exittrading.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;

/**
 * Persists the current Kite session (user, tokens, expiry) on shutdown and
 * restores it on startup so the app can reconnect without a fresh login.
 */
@Component
public class SessionPersistence {

    private static final Logger log = LoggerFactory.getLogger(SessionPersistence.class);
    private static final Path SESSION_FILE = Path.of("logs", "kite-session.json");

    private final KiteSessionManager sessionManager;
    private final IstClock clock;
    private final AdminService adminService;
    private final DepthStreamService depthStreamService;

    @Value("${kite.apiKey}")
    private String apiKey;

    public SessionPersistence(KiteSessionManager sessionManager,
                              IstClock clock,
                              AdminService adminService,
                              DepthStreamService depthStreamService) {
        this.sessionManager = sessionManager;
        this.clock = clock;
        this.adminService = adminService;
        this.depthStreamService = depthStreamService;
    }

    @PostConstruct
    public void tryRestore() {
        try {
            // Only attempt when KiteConnect is available
            Class<?> kiteClass = Class.forName("com.zerodhatech.kiteconnect.KiteConnect");
            if (!Files.exists(SESSION_FILE)) {
                log.info("No saved Kite session found at {} — skipping restore", SESSION_FILE);
                return;
            }
            byte[] json = Files.readAllBytes(SESSION_FILE);
            SavedSession saved = new ObjectMapper().readValue(json, SavedSession.class);
            if (saved == null || saved.accessToken == null || saved.accessToken.isBlank()) {
                log.warn("Saved Kite session missing access token; file {}", SESSION_FILE);
                return;
            }
            ZonedDateTime expiry = null;
            try { expiry = saved.expiry != null ? ZonedDateTime.parse(saved.expiry) : null; } catch (Exception ignored) {}
            if (expiry == null || expiry.isBefore(clock.now())) {
                log.warn("Saved Kite session is expired; skipping restore and removing {}", SESSION_FILE);
                try { Files.deleteIfExists(SESSION_FILE); } catch (Exception ignoredDel) {}
                return;
            }
            Object kite = kiteClass.getConstructor(String.class).newInstance(apiKey);
            invokeIfPresent(kiteClass, kite, "setAccessToken", saved.accessToken);
            invokeIfPresent(kiteClass, kite, "setPublicToken", saved.publicToken);

            sessionManager.initializeSession(kite, saved.userName != null ? saved.userName : "unknown", expiry);
            log.info("Restored Kite session for {} from {}", saved.userName, SESSION_FILE);

            // Best-effort to ensure user and kick off streaming
            try {
                if (adminService != null && saved.userName != null) {
                    adminService.ensureUserExists(saved.userName, saved.userName);
                    var userAccount = adminService.findByUsername(saved.userName);
                    if (depthStreamService != null && userAccount != null) {
                        depthStreamService.startForUser(userAccount);
                    }
                }
            } catch (Exception ex) {
                log.warn("Post-restore hooks failed: {}", ex.getMessage());
            }
        } catch (ClassNotFoundException e) {
            log.info("KiteConnect jar not present; skipping session restore");
        } catch (Exception ex) {
            log.error("Failed to restore Kite session", ex);
        }
    }

    @PreDestroy
    public void dumpOnShutdown() {
        try {
            Object kite = null;
            try {
                kite = sessionManager.getKiteConnect();
            } catch (Exception ignored) { }
            if (kite == null) return;
            String userName = sessionManager.getUserName();
            ZonedDateTime expiry = sessionManager.getExpiry();
            String accessToken = invokeString(kite, "getAccessToken");
            String publicToken = invokeString(kite, "getPublicToken");

            SavedSession saved = new SavedSession();
            saved.userName = userName;
            saved.apiKey = apiKey;
            saved.accessToken = accessToken;
            saved.publicToken = publicToken;
            saved.expiry = (expiry != null ? expiry.toString() : null);

            try { Files.createDirectories(SESSION_FILE.getParent()); } catch (Exception ignored) {}
            byte[] json = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(saved);
            Files.write(SESSION_FILE, json);
            log.info("Persisted Kite session for {} to {}", userName, SESSION_FILE);
        } catch (Exception ex) {
            log.error("Failed to persist Kite session", ex);
        }
    }

    private static void invokeIfPresent(Class<?> targetType, Object target, String methodName, Object argument) {
        if (argument == null) return;
        try {
            Method method = targetType.getMethod(methodName, String.class);
            method.invoke(target, argument.toString());
        } catch (Exception ignored) { }
    }

    private static String invokeString(Object target, String method) {
        try {
            Method m = target.getClass().getMethod(method);
            Object out = m.invoke(target);
            return out != null ? out.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // Minimal DTO for JSON serialization
    private static class SavedSession {
        public String userName;
        public String apiKey;
        public String accessToken;
        public String publicToken;
        public String expiry; // ISO-8601 ZonedDateTime
    }
}
