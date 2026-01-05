package com.exittrading.app.service.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ShutdownService {

    private static final Logger log = LoggerFactory.getLogger(ShutdownService.class);

    private final ConfigurableApplicationContext context;
    private final CoalescingPersistenceService coalescingPersistenceService;
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);

    public ShutdownService(ConfigurableApplicationContext context,
                           CoalescingPersistenceService coalescingPersistenceService) {
        this.context = context;
        this.coalescingPersistenceService = coalescingPersistenceService;
    }

    public boolean requestShutdown() {
        if (!shutdownRequested.compareAndSet(false, true)) {
            return false;
        }
        log.warn("Graceful shutdown requested.");
        try {
            coalescingPersistenceService.flushBuffer();
        } catch (Exception e) {
            log.warn("Pre-shutdown flush failed: {}", e.getMessage());
        }

        Thread shutdownThread = new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            try {
                int code = SpringApplication.exit(context, () -> 0);
                System.exit(code);
            } catch (Exception e) {
                log.error("Graceful shutdown failed", e);
            }
        }, "graceful-shutdown");
        shutdownThread.setDaemon(false);
        shutdownThread.start();
        return true;
    }
}
