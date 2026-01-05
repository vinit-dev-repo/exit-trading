package com.exittrading.app.service.core;

import com.exittrading.app.domain.KiteSession;
import com.exittrading.app.repository.KiteSessionRepository;
import com.zerodhatech.kiteconnect.KiteConnect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Persists the current Kite session (user, tokens, expiry) to Database on shutdown and
 * restores it on startup so the app can reconnect without a fresh login.
 * Refactored to use strong typing and remove reflection.
 */
@Component
@ConditionalOnClass(name = "com.zerodhatech.kiteconnect.KiteConnect")
@ConditionalOnProperty(name = "kite.enabled", havingValue = "true", matchIfMissing = true)
public class SessionPersistence {

    private static final Logger log = LoggerFactory.getLogger(SessionPersistence.class);

    private final KiteSessionManager sessionManager;
    private final IstClock clock;
    private final AdminService adminService;
    private final DepthStreamService depthStreamService;
    private final KiteSessionRepository sessionRepository;
    private final SettingsService settingsService;

    public SessionPersistence(KiteSessionManager sessionManager,
                              IstClock clock,
                              AdminService adminService,
                              DepthStreamService depthStreamService,
                              KiteSessionRepository sessionRepository,
                              SettingsService settingsService) {
        this.sessionManager = sessionManager;
        this.clock = clock;
        this.adminService = adminService;
        this.depthStreamService = depthStreamService;
        this.sessionRepository = sessionRepository;
        this.settingsService = settingsService;
    }

    @PostConstruct
    public void tryRestore() {
        try {
            // Use custom query with JOIN FETCH
            List<KiteSession> candidates = sessionRepository.findActiveSessionsWithUser();
            if (candidates.isEmpty()) {
                log.info("TryRestore: No active Kite session found in database.");
                return;
            }
            KiteSession saved = candidates.get(0);
            log.info("TryRestore: Found candidate session ID={}, User={}, Expiry={}", saved.getSessionId(), (saved.getUser() != null ? saved.getUser().getUsername() : "null"), saved.getExpiresAt());
            
            if (saved.getAccessToken() == null || saved.getAccessToken().isBlank()) {
                log.warn("TryRestore: Saved Kite session missing access token");
                return;
            }
            ZonedDateTime expiry = saved.getExpiresAt();
            ZonedDateTime now = clock.now();
            if (expiry == null || expiry.isBefore(now)) {
                log.warn("TryRestore: Session expired. Expiry: {}, Now: {}. Marking inactive.", expiry, now);
                saved.setActive(false);
                sessionRepository.save(saved);
                return;
            }
            
            String apiKey = settingsService != null
                    ? settingsService.getString("kite.apiKey", null)
                    : System.getenv("KITE_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                log.warn("TryRestore: Kite API key not configured.");
                return;
            }
            KiteConnect kite = new KiteConnect(apiKey);
            kite.setAccessToken(saved.getAccessToken());
            kite.setPublicToken(saved.getPublicToken());

            String userName = saved.getUser() != null ? saved.getUser().getUsername() : "unknown";
            sessionManager.initializeSession(kite, userName, expiry);
            log.info("Restored Kite session for {} (ID: {})", userName, saved.getSessionId());

            // Best-effort to ensure user and kick off streaming
            try {
                if (adminService != null && !userName.equals("unknown")) {
                    adminService.ensureUserExists(userName, userName);
                    var userAccount = adminService.findByUsername(userName);
                    if (depthStreamService != null && userAccount != null) {
                        depthStreamService.startForUser(userAccount);
                    }
                }
            } catch (Exception ex) {
                log.warn("Post-restore hooks failed: {}", ex.getMessage());
            }
        } catch (Exception ex) {
            log.error("Failed to restore Kite session", ex);
        }
    }

    @PreDestroy
    public void persistSession() {
        try {
            KiteConnect kite = sessionManager.getKiteConnect();
            if (kite == null) return;
            
            String userName = sessionManager.getUserName();
            ZonedDateTime expiry = sessionManager.getExpiry();
            String accessToken = kite.getAccessToken();
            String publicToken = kite.getPublicToken();
            
            // Generate a session ID if one isn't clear, or update existing active one
            KiteSession ks = sessionRepository.findTopByIsActiveTrueOrderByUpdatedAtDesc().orElse(new KiteSession());
            
            if (ks.getSessionId() == null) {
                ks.setSessionId(userName + "_" + System.currentTimeMillis());
                ks.setLoginTime(ZonedDateTime.now()); // Set login time for new sessions
            }
            
            if (adminService != null) {
                ks.setUser(adminService.findByUsername(userName));
            }
            String apiKey = settingsService != null
                    ? settingsService.getString("kite.apiKey", null)
                    : System.getenv("KITE_API_KEY");
            ks.setApiKey(apiKey);
            ks.setAccessToken(accessToken);
            ks.setPublicToken(publicToken);
            ks.setExpiresAt(expiry);

            ks.setUpdatedAt(ZonedDateTime.now());
            ks.setActive(expiry != null && expiry.isAfter(clock.now()));

            sessionRepository.save(ks);
            log.info("Persisted Kite session for {} to DB", userName);
        } catch (Exception ex) {
            log.error("Failed to persist Kite session", ex);
        }
    }
}
