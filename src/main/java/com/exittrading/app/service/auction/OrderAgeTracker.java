package com.exittrading.app.service.auction;

import com.exittrading.app.dto.DepthView;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

/**
 * Tracks the age (time-in-force) of specific price/quantity levels in the order book.
 * This is critical for calculating P*_robust (Age-Weighted Equilibrium Price).
 * 
 * Logic:
 * - If a price level (Price + Qty) persists across snapshots, its age increases.
 * - If Qty changes or Price disappears, age resets to 0 (now).
 */
@Service
public class OrderAgeTracker {

    private static class LevelState {
        final long quantity;
        final Instant firstSeen;
        
        LevelState(long quantity, Instant firstSeen) {
            this.quantity = quantity;
            this.firstSeen = firstSeen;
        }
    }

    // Map<Symbol, Map<Price, LevelState>>
    // We separate Bids and Asks or just track by Price signed? 
    // Usually Bids and Asks don't overlap. We can treat them distinctly.
    // Let's use two maps per symbol.
    private final Map<String, Map<Double, LevelState>> bidTracker = new ConcurrentHashMap<>();
    private final Map<String, Map<Double, LevelState>> askTracker = new ConcurrentHashMap<>();

    public void update(String symbol, DepthView view) {
        if (symbol == null || view == null) return;

        updateSide(bidTracker.computeIfAbsent(symbol, k -> new HashMap<>()), view.getBuyLevels());
        updateSide(askTracker.computeIfAbsent(symbol, k -> new HashMap<>()), view.getSellLevels());
    }

    private void updateSide(Map<Double, LevelState> state, List<DepthView.Level> currentLevels) {
        if (currentLevels == null) {
            state.clear();
            return;
        }

        Map<Double, LevelState> nextState = new HashMap<>(); // temporary state for this snapshot
        Instant now = Instant.now();

        for (DepthView.Level lvl : currentLevels) {
            Double p = lvl.getPrice();
            long q = lvl.getQuantity();
            
            LevelState existing = state.get(p);
            if (existing != null && existing.quantity == q) {
                // Persist existing state (same age)
                nextState.put(p, existing);
            } else {
                // New or Changed -> Reset age
                nextState.put(p, new LevelState(q, now));
            }
        }
        
        // Replace old state with new state (implicitly removes vanished levels)
        state.clear();
        state.putAll(nextState);
    }

    /**
     * Returns the weight (0.0 to 1.0) for a given price level based on its age.
     * Formula: min(1.0, age_seconds / 60)
     */
    public double getWeight(String symbol, double price, boolean isBid) {
        Map<Double, LevelState> tracker = isBid ? bidTracker.get(symbol) : askTracker.get(symbol);
        if (tracker == null) return 0.0;
        
        LevelState st = tracker.get(price);
        if (st == null) return 0.0; // New or unknown
        
        long ageSec = java.time.Duration.between(st.firstSeen, Instant.now()).getSeconds();
        return Math.min(1.0, ageSec / 60.0);
    }
    
    /**
     * Clears all tracking data (e.g. on session end)
     */
    public void clear() {
        bidTracker.clear();
        askTracker.clear();
    }
}
