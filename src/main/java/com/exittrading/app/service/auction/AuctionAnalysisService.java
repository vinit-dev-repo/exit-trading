package com.exittrading.app.service.auction;

import com.exittrading.app.dto.DepthView;
import com.exittrading.app.dto.DetectionSummary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuctionAnalysisService {

    // Configurable knobs (simple placeholders; wire to application.yml)
    @Value("${auction.detector.ewmaHalfLifeSec:20}")
    private int ewmaHalfLifeSec;
    @Value("${auction.detector.scoreTrigger:2.2}")
    private double scoreTrigger;
    @Value("${auction.detector.swingConfirm:0.30}")
    private double swingConfirm;

    // In-memory EWMA state per symbol for sell/buy pressure
    private final Map<String, Double> ewmaSell = new ConcurrentHashMap<>();
    private final Map<String, Double> ewmaBuy = new ConcurrentHashMap<>();

    // Simple EWMA updater with time-discrete alpha
    private double updateEwma(Map<String, Double> state, String key, double x) {
        double alpha = 1.0 - Math.exp(-Math.log(2.0) / Math.max(ewmaHalfLifeSec, 1));
        double prev = state.getOrDefault(key, x);
        double next = alpha * x + (1 - alpha) * prev;
        state.put(key, next);
        return next;
    }

    public List<DetectionSummary> summarize(List<DepthView> views) {
        if (views == null || views.isEmpty()) return List.of();
        List<DetectionSummary> out = new ArrayList<>();
        for (DepthView v : views) {
            if (v == null || v.getTradingsymbol() == null) continue;
            String sym = normalize(v.getTradingsymbol());

            long buyQty = Math.max(0, v.getBuyQuantity());
            long sellQty = Math.max(0, v.getSellQuantity());
            double obi = (buyQty + sellQty) > 0 ? ((double)sellQty - (double)buyQty) / ((double)sellQty + (double)buyQty) : 0.0;

            // Proximity-weighted pressure using top levels if available
            double sellPressure = weightedPressure(v.getSellLevels());
            double buyPressure = weightedPressure(v.getBuyLevels());
            double ewSell = updateEwma(ewmaSell, sym, sellPressure);
            double ewBuy = updateEwma(ewmaBuy, sym, buyPressure);
            double swing = ewSell > 0 ? (sellPressure - ewSell) / (ewSell + 1e-9) : 0.0;

            // Minimal placeholder score: time weighting and level checks will come later
            double score = 0.0;
            if (swing >= swingConfirm) score += 1.2;
            if (obi > 0) score += 0.8;

            boolean confirmed = score >= scoreTrigger;

            // Recommend limit: best bid minus a tick if we have levels; else null
            Double bestBid = v.getBuyLevels() != null && !v.getBuyLevels().isEmpty() ? v.getBuyLevels().get(0).getPrice() : null;
            Double recommended = bestBid != null ? (bestBid - estimateTick(bestBid)) : null;

            DetectionSummary d = new DetectionSummary();
            d.setTradingsymbol(sym);
            d.setObi(round(obi, 4));
            d.setSwing(round(swing, 3));
            d.setSellSpikeScore(round(score, 2));
            d.setConfirmed(confirmed);
            d.setLikelySpoof(false); // TODO: add spoof heuristic
            d.setRecommendedLimit(recommended != null ? round(recommended, 2) : null);
            out.add(d);
        }
        return out;
    }

    private static String normalize(String s) {
        if (s == null) return null;
        int idx = s.indexOf(":");
        return idx > -1 ? s.substring(idx + 1) : s;
    }

    private static double weightedPressure(List<DepthView.Level> levels) {
        if (levels == null || levels.isEmpty()) return 0.0;
        double sum = 0.0;
        for (int i = 0; i < levels.size(); i++) {
            DepthView.Level lvl = levels.get(i);
            double w = 1.0 / (i + 1);
            int qty = lvl != null ? lvl.getQuantity() : 0;
            sum += w * Math.max(0, qty);
        }
        return sum;
    }

    private static double estimateTick(double price) {
        // Minimal tick estimator; replace with broker symbol metadata later
        if (price >= 1000) return 0.5;
        if (price >= 100) return 0.05;
        return 0.01;
    }

    private static Double round(Double v, int scale) {
        if (v == null) return null;
        double factor = Math.pow(10, scale);
        return Math.round(v * factor) / factor;
    }
}

