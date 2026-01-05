package com.exittrading.app.service.core;

import com.exittrading.app.domain.UserAccount;
import com.exittrading.app.repository.LoggingScripRepository;
import com.exittrading.app.repository.UserAccountRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CleanupService {

    private static final Logger log = LoggerFactory.getLogger(CleanupService.class);

    private final UserAccountRepository userRepository;
    private final LoggingScripRepository loggingScripRepository;
    private final com.exittrading.app.service.scrip.LoggingScripService loggingScripService;

    public CleanupService(UserAccountRepository userRepository, 
                          LoggingScripRepository loggingScripRepository,
                          @org.springframework.context.annotation.Lazy com.exittrading.app.service.scrip.LoggingScripService loggingScripService) {
        this.userRepository = userRepository;
        this.loggingScripRepository = loggingScripRepository;
        this.loggingScripService = loggingScripService;
    }

    @PostConstruct
    @Transactional
    public void onStartup() {
        // 1. Remove demo user
        try {
            userRepository.findByUsername("demo").ifPresent(user -> {
                log.info("Removing demo user and associated data...");
                loggingScripRepository.deleteAll(loggingScripRepository.findByUserOrderByAddedAtDesc(user));
                userRepository.delete(user);
                log.info("Demo user removed.");
            });
        } catch (Exception e) {
            log.warn("Failed to remove demo user: {}", e.getMessage());
        }

        // 2. Sync instruments for all remaining users
        try {
            java.util.List<UserAccount> users = userRepository.findAll();
            for (UserAccount user : users) {
                try {
                    // DISABLED: This causes massive subscription explosion on startup (Rate Limits).
                    // Subscriptions should only happen for Active Watchlist + Holdings, which are handled by DepthStreamService.subscribeHoldings()
                    // loggingScripService.syncInstruments(user.getUsername());
                    log.info("Startup syncInstruments disabled for user {} to prevent API flood.", user.getUsername());
                } catch (Exception ex) {
                    log.warn("Failed to sync instruments for user {}: {}", user.getUsername(), ex.getMessage());
                }
            }
        } catch (Exception e) {
             log.warn("Startup sync failed", e);
        }
    }
}
