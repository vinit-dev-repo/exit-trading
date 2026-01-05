package com.exittrading.app.service.core;

import com.exittrading.app.dto.DepthView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service to buffer high-frequency MarketDepth updates and persist only the latest snapshot
 * per token at a fixed rate (Coalescing Pattern).
 * This prevents DB saturation when "significant-change-pct" is 0.0 or low.
 */
@Service
public class CoalescingPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(CoalescingPersistenceService.class);

    private final DepthService depthService;
    private final SettingsService settingsService;
    
    // Map<Token, LatestSnapshot>
    // We utilize ConcurrentHashMap to allow lock-free updates from the Ticker thread.
    private final Map<Long, DepthView> updateBuffer = new ConcurrentHashMap<>();
    
    // Resilience: Track last successful persist time to inform fallback logic
    private final Map<Long, Long> lastPersistTime = new ConcurrentHashMap<>();
    
    // Change Detection: Track hash of last persisted bids/asks to avoid duplicates
    private final Map<Long, Integer> lastPersistedHash = new ConcurrentHashMap<>();
    private final Map<Long, Double> lastPersistedLtp = new ConcurrentHashMap<>();

    private final AtomicLong lastFlushMs = new AtomicLong(0L);

    // Metrics
    private final AtomicLong totalTicksReceived = new AtomicLong(0);
    private final AtomicLong totalPersisted = new AtomicLong(0);

    public CoalescingPersistenceService(DepthService depthService, SettingsService settingsService) {
        this.depthService = depthService;
        this.settingsService = settingsService;
    }

    /**
     * Called by Ticker thread (DepthStreamService) for every single tick.
     * Keep this extremely lightweight (non-blocking).
     */
    public void offer(Long token, DepthView snapshot) {
        if (token == null || snapshot == null) return;
        totalTicksReceived.incrementAndGet();
        updateBuffer.put(token, snapshot);
    }

    /**
     * Called every 1000ms to flush the buffer to DB.
     * This decouples the arrival rate (e.g. 1000/sec) from the write rate (fixed 1/sec).
     */
    @Scheduled(fixedRate = 250)
    public void flushBuffer() {
        long debounceMs = settingsService.getLong("app.config.market.debounce-ms", 1000);
        long nowMs = System.currentTimeMillis();
        long last = lastFlushMs.get();
        if (nowMs - last < debounceMs) return;
        if (!lastFlushMs.compareAndSet(last, nowMs)) return;

        if (updateBuffer.isEmpty()) return;

        int count = 0;
        // Iterate over the current keys.
        // Note: iterators on CHM are weakly consistent, which is fine for this use case.
        // We want to clear the entry after processing to avoid re-persisting stale data next second
        // if no new tick arrived.
        
        for (Long token : updateBuffer.keySet()) {
            DepthView snapshot = updateBuffer.remove(token);
            if (snapshot != null) {
                try {
                    // Change Detection Logic
                    int currentHash = computeDepthHash(snapshot);
                    Integer lastHash = lastPersistedHash.get(token);
                    long lastTime = lastPersistTime.getOrDefault(token, 0L);
                    long now = System.currentTimeMillis();
                    
                    // Persist if:
                    // 1. First time seeing this token
                    // 2. Hash has changed (Bids/Asks changed)
                    // 3. Max Age exceeded (Heartbeat)
                    boolean hasChanged = (lastHash == null) || (lastHash != currentHash);
                    long maxAgeMs = settingsService.getLong("app.config.market.max-age-ms", 60000);
                    boolean isStale = (now - lastTime) > maxAgeMs;

                    double significantPct = settingsService.getDouble("app.config.market.significant-change-pct", 0.0);
                    boolean ltpMoved = isSignificantLtpChange(token, snapshot, significantPct);
                    
                    // Note: DepthService.persistDepth checks "suppression" based on sell pressure.
                    // We shouldn't block here if it changes, assuming DepthService handles the business suppression.
                    // But we MUST block here if it is IDENTICAL to last time.
                    
                    if (hasChanged || ltpMoved || isStale) {
                        depthService.persistDepth(snapshot, String.valueOf(token), false);
                        lastPersistTime.put(token, now);
                        lastPersistedHash.put(token, currentHash);
                        recordLtp(token, snapshot);
                        count++;
                    }
                } catch (Exception e) {
                    log.error("Failed to coalesce-persist token {}", token, e);
                }
            }
        }
        
        if (count > 0) {
            totalPersisted.addAndGet(count);
            // Log periodically or if verbose needed. 
            // For now, let's keep it quiet unless in debug/trace, or maybe once a minute.
            log.debug("Coalescer flushed {} snapshots to DB. (Buffer size: {})", count, updateBuffer.size());
        }
    }
    
    // Getters for resilience checks
    public boolean hasRecentData(Long token) {
        return updateBuffer.containsKey(token);
    }
    
    public long getTotalTicks() {
        return totalTicksReceived.get();
    }
    
    public long getTotalPersisted() {
        return totalPersisted.get();
    }
    
    public long getLastPersistTime(String tokenStr) {
        try {
            Long t = Long.parseLong(tokenStr);
            return lastPersistTime.getOrDefault(t, 0L);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private int computeDepthHash(DepthView v) {
        if (v == null) return 0;
        // We care about Bids and Asks structure (Price + Qty + Orders)
        // We do NOT include LTP/LTT/Volume in this hash because 
        // the requirement is "only if there is any change in either bid or asks".
        // If only LTP changes but depth is identical, we SKIP persistence (unless max age).
        int result = 1;
        result = 31 * result + hashLevels(v.getBuyLevels());
        result = 31 * result + hashLevels(v.getSellLevels());
        return result;
    }

    private int hashLevels(java.util.List<DepthView.Level> levels) {
        if (levels == null) return 0;
        int result = 1;
        for (DepthView.Level level : levels) {
            if (level == null) {
                result = 31 * result;
                continue;
            }
            int priceHash = Double.hashCode(level.getPrice());
            int qtyHash = Integer.hashCode(level.getQuantity());
            int orderHash = Integer.hashCode(level.getOrders());
            result = 31 * result + priceHash;
            result = 31 * result + qtyHash;
            result = 31 * result + orderHash;
        }
        return result;
    }

    private boolean isSignificantLtpChange(Long token, DepthView v, double thresholdPct) {
        if (v == null || v.getLtp() == null) return false;
        double ltp = v.getLtp().doubleValue();
        if (ltp <= 0) return false;
        Double last = lastPersistedLtp.get(token);
        if (last == null || last <= 0) return true;
        double pct = Math.abs(ltp - last) / last * 100.0;
        return pct >= Math.max(0.0, thresholdPct);
    }

    private void recordLtp(Long token, DepthView v) {
        if (v == null || v.getLtp() == null) return;
        lastPersistedLtp.put(token, v.getLtp().doubleValue());
    }
}
