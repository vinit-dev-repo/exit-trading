package com.exittrading.app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

@Component
public class KiteSessionManager {

    private static final Logger log = LoggerFactory.getLogger(KiteSessionManager.class);

    private final ExecutorService executionPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(),
            runnable -> {
                Thread t = new Thread(runnable);
                t.setName("order-exec-" + t.getId());
                // Use non-daemon to avoid premature JVM exit on certain runtimes
                // t.setDaemon(true);
                t.setUncaughtExceptionHandler((thr, ex) ->
                        log.error("Uncaught exception in {}", thr.getName(), ex));
                return t;
            });

    private final ExecutorService marketDataPool = Executors.newCachedThreadPool(runnable -> {
        Thread t = new Thread(runnable);
        t.setName("depth-pool-" + t.getId());
        // Use non-daemon to avoid premature JVM exit on certain runtimes
        // t.setDaemon(true);
        t.setUncaughtExceptionHandler((thr, ex) ->
                log.error("Uncaught exception in {}", thr.getName(), ex));
        return t;
    });

    private final ScheduledExecutorService sessionRefresher = new ScheduledThreadPoolExecutor(1);

    private final IstClock clock;

    private Object kiteConnect;
    private ZonedDateTime expiry;
    private String userName;

    public KiteSessionManager(IstClock clock) {
        this.clock = clock;
    }

    public synchronized Object getKiteConnect() {
        if (kiteConnect == null) {
            throw new IllegalStateException("Kite session not initialized. Please login through /api/admin/session/login");
        }
        if (expiry != null && expiry.isBefore(clock.now().plusMinutes(5))) {
            log.warn("Kite session nearing expiry. Please renew.");
        }
        return kiteConnect;
    }

    public ExecutorService getExecutionPool() {
        return executionPool;
    }

    public ExecutorService getMarketDataPool() {
        return marketDataPool;
    }

    public synchronized void initializeSession(Object newSession, String userName, ZonedDateTime expiry) {
        this.kiteConnect = newSession;
        this.expiry = expiry != null ? expiry : clock.now().plusHours(24);
        this.userName = userName;
        log.info("Kite session established for {}", userName);
    }

    public synchronized void invalidateSession() {
        if (kiteConnect != null) {
            kiteConnect = null;
            expiry = null;
            userName = null;
            log.info("Kite session invalidated");
        }
    }

    public synchronized ZonedDateTime getExpiry() {
        return expiry;
    }

    public synchronized String getUserName() {
        return userName;
    }

    public void scheduleRenewal(Runnable renewalTask, Duration refreshBeforeExpiry) {
        sessionRefresher.submit(() -> {
            try {
                if (expiry == null) {
                    return;
                }
                long delay = Duration.between(clock.now(), expiry.minus(refreshBeforeExpiry)).toMillis();
                if (delay > 0) {
                    sessionRefresher.schedule(renewalTask, delay, java.util.concurrent.TimeUnit.MILLISECONDS);
                } else {
                    renewalTask.run();
                }
            } catch (Exception ex) {
                log.error("Failed to schedule session renewal", ex);
            }
        });
    }
}
