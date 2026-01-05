package com.exittrading.app.service.auction;

import com.exittrading.app.dto.DepthView;
import com.exittrading.app.dto.DetectionSummary;
import com.exittrading.app.config.SessionSlotConfig;
import com.exittrading.app.domain.SessionSlot;
import com.exittrading.app.service.core.IstClock;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service to analyze auction pressure, detect spoofing, and compute ESM Exit Indicators.
 * Implements Phase 2 of ESM Exit Strategy (Equilibrium, Trend, Downside Gates).
 */
@Service
public class AuctionAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AuctionAnalysisService.class);

    private final EntityManager entityManager;
    private final IstClock clock;
    private final SessionSlotConfig slotConfig;
    private final com.exittrading.app.service.core.SettingsService settingsService;
    
    // ESM Configs
    private static final double TREND_THRESHOLD = -0.2; // Ticks per minute
    private static final int HISTORY_WINDOW_MIN = 5;

    // In-memory EWMA state per symbol for sell/buy pressure
    private final Map<String, Double> ewmaSell = new ConcurrentHashMap<>();
    private final Map<String, Double> ewmaBuy = new ConcurrentHashMap<>();
    
    // ESM State: Weighted Medians for TrendDelta
    // Map<Symbol, Deque<PricePoint>> where PricePoint = {timestamp, robustPrice}
    private final Map<String, Deque<PricePoint>> priceHistory = new ConcurrentHashMap<>();
    private final Map<String, SessionDriftCache> sessionDriftCache = new ConcurrentHashMap<>();

    public AuctionAnalysisService(EntityManager entityManager, IstClock clock, SessionSlotConfig slotConfig,
                                  com.exittrading.app.service.core.SettingsService settingsService) {
        this.entityManager = entityManager;
        this.clock = clock;
        this.slotConfig = slotConfig;
        this.settingsService = settingsService;
    }
    
    private static class PricePoint {
        final long timeMillis;
        final double price;
        PricePoint(long t, double p) { timeMillis = t; price = p; }
    }

    private static class SessionWindow {
        final ZonedDateTime currentStart;
        final ZonedDateTime currentEnd;
        final ZonedDateTime prevStart;
        final ZonedDateTime prevEnd;
        SessionWindow(ZonedDateTime currentStart, ZonedDateTime currentEnd, ZonedDateTime prevStart, ZonedDateTime prevEnd) {
            this.currentStart = currentStart;
            this.currentEnd = currentEnd;
            this.prevStart = prevStart;
            this.prevEnd = prevEnd;
        }
    }

    private static class SessionDriftCache {
        final ZonedDateTime sessionStart;
        final ZonedDateTime computedAt;
        final double value;
        SessionDriftCache(ZonedDateTime sessionStart, ZonedDateTime computedAt, double value) {
            this.sessionStart = sessionStart;
            this.computedAt = computedAt;
            this.value = value;
        }
    }

    /**
     * DTO for ESM Indicators
     */
    public static class EsmIndicators {
        public Double pRaw;     // Raw Equilibrium
        public Double pRobust;  // Age-Weighted Equilibrium
        public Double pLimit;   // Execution Limit (LC)
        public Double uc;       // Effective UC
        public boolean ucLock;
        public boolean ucBreakExpected;
        public boolean dDown;   // The signal to exit
        public double imbalance;
        public double trendDeltaTpm;
        public double sessionDrift;
        public String reason;
    }

    /**
     * Updates the EWMA state for a given key with value x.
     */
    private double updateEwma(Map<String, Double> state, String key, double x) {
        int halfLife = settingsService.getInt("auction.detector.ewmaHalfLifeSec", 20);
        double alpha = 1.0 - Math.exp(-Math.log(2.0) / Math.max(halfLife, 1));
        double prev = state.getOrDefault(key, x);
        double next = alpha * x + (1 - alpha) * prev;
        state.put(key, next);
        return next;
    }

    /**
     * Summarizes the auction state for a list of depth views.
     */
    public List<DetectionSummary> summarize(List<DepthView> views) {
        // ... (Keep existing simple logic for UI visualization if needed, or deprecate)
        // For brevity in this refactor, I will retain the original 'summarize' logic 
        // but it is largely superseded by the robust 'calculateIndicators'.
        // We can keep it for backward compatibility with the Dashboard 'Analysis' tab which uses DetectionSummary.
        return summarizeLegacy(views);
    }
    
    private List<DetectionSummary> summarizeLegacy(List<DepthView> views) {
        if (views == null || views.isEmpty()) return List.of();
        List<DetectionSummary> out = new ArrayList<>();
        for (DepthView v : views) {
            // ... (Copy of original logic for UI/Dashboard compatibility)
            // Ideally we migrate Dashboard to use EsmIndicators too.
             if (v == null || v.getTradingsymbol() == null) continue;
            String sym = normalize(v.getTradingsymbol());
            long buyQty = Math.max(0, v.getBuyQuantity());
            long sellQty = Math.max(0, v.getSellQuantity());
            double obi = (buyQty + sellQty) > 0 ? ((double)sellQty - (double)buyQty) / ((double)sellQty + (double)buyQty) : 0.0;
            double sellPressure = weightedPressure(v.getSellLevels());
            double buyPressure = weightedPressure(v.getBuyLevels());
            double ewSell = updateEwma(ewmaSell, sym, sellPressure);
            updateEwma(ewmaBuy, sym, buyPressure);
            double swingThreshold = settingsService.getDouble("auction.detector.swingConfirm", 0.30);
            double swing = ewSell > 0 ? (sellPressure - ewSell) / (ewSell + 1e-9) : 0.0;
            double score = 0.0;
            if (swing >= swingThreshold) score += 1.2;
            if (obi > 0) score += 0.8;
            boolean likelySpoof = detectSpoofing(v);
            if (likelySpoof) score += 0.5;
            
            double trigger = settingsService.getDouble("auction.detector.scoreTrigger", 2.2);
            boolean confirmed = score >= trigger;

            DetectionSummary d = new DetectionSummary();
            d.setTradingsymbol(sym);
            d.setObi(round(obi, 4));
            d.setSwing(round(swing, 3));
            d.setSellSpikeScore(round(score, 2));
            d.setConfirmed(confirmed);
            d.setLikelySpoof(likelySpoof);
            d.setRecommendedLimit(null);
            out.add(d);
        }
        return out;
    }
    
    // Expose score trigger for normalization in other services
    public double getScoreTrigger() {
        return settingsService.getDouble("auction.detector.scoreTrigger", 2.2);
    }

    /**
     * Core ESM Phase 2 Logic: Calculate Indicators and Triggers
     */
    public EsmIndicators calculateIndicators(DepthView snapshot, OrderAgeTracker ageTracker, double prevClose, Double open0930, Double manualUC) {
        EsmIndicators ind = new EsmIndicators();
        if (snapshot == null) return ind;
        
        // 1. Derived Inputs
        Double lc = snapshot.getLowerCircuit() != null ? snapshot.getLowerCircuit().doubleValue() : null;
        ind.pLimit = lc; // P_map = LC
        
        Double uc = manualUC;
        if (uc == null) uc = snapshot.getUpperCircuit() != null ? snapshot.getUpperCircuit().doubleValue() : null;
        
        // Requirement: "If UC is missing... using Open_0930 and P_prev_close"
        if (uc == null && open0930 != null && prevClose > 0) {
            if (open0930 > prevClose) {
                uc = open0930;
            } else {
                // UC unknown, skip UC-based triggers
                uc = null; 
            }
        }
        ind.uc = uc;
        
        double ltp = snapshot.getLtp() != null ? snapshot.getLtp().doubleValue() : 0.0;
        double tickSize = resolveTickSize(snapshot);

        // 2. Equilibrium
        ind.pRaw = calculateEquilibrium(snapshot, null, null); // Unweighted
        ind.pRobust = calculateEquilibrium(snapshot, ageTracker, normalize(snapshot.getTradingsymbol())); // Age-weighted
        
        // Fallback for robust if tracker empty: use raw
        if (ind.pRobust == null) ind.pRobust = ind.pRaw;
        
        // 3. Trends (TrendDelta)
        // Update history first
        if (ind.pRobust != null) {
            updatePriceHistory(snapshot.getTradingsymbol(), ind.pRobust);
        }
        ind.trendDeltaTpm = calculateTrendDeltaTpm(snapshot.getTradingsymbol(), tickSize);
        ind.sessionDrift = calculateSessionDrift(snapshot.getTradingsymbol()); // Placeholder logic for Drift
        
        // 4. Core Metrics
        long sumBid = sumQty(snapshot.getBuyLevels());
        long sumAsk = sumQty(snapshot.getSellLevels());
        ind.imbalance = (sumBid + sumAsk) > 0 ? (double)(sumBid - sumAsk) / (sumBid + sumAsk) : 0.0;

        // 5. Triggers
        // UC Lock: LTP at UC AND P* at UC
        boolean ucDefined = (uc != null && uc > 0);
        if (ucDefined && ind.pRobust != null) {
            boolean ltpAtUc = Math.abs(ltp - uc) <= tickSize;
            boolean eqAtUc = Math.abs(ind.pRobust - uc) <= tickSize;
            ind.ucLock = ltpAtUc && eqAtUc;
            
            boolean eqBreaking = ind.pRobust <= (uc - tickSize);
            boolean weakTrend = (ind.trendDeltaTpm <= TREND_THRESHOLD || ind.sessionDrift <= -2 * tickSize);
            boolean negImbalance = ind.imbalance <= -0.1;
            
            // UC Break Expected: LTP at UC but Eq below UC, weak trend, neg imbalance
            ind.ucBreakExpected = ltpAtUc && eqBreaking && weakTrend && negImbalance;
        } else {
            ind.ucLock = false;
            ind.ucBreakExpected = false;
        }
        
        // D_down: (UC_break) OR (TrendWeak + NegImbalance)
        boolean trendSignal = (ind.trendDeltaTpm <= TREND_THRESHOLD || ind.sessionDrift <= -2 * tickSize) && (ind.imbalance <= -0.1);
        
        ind.dDown = ind.ucBreakExpected || trendSignal;
        
        // Reason string
        if (ind.dDown) {
            ind.reason = ind.ucBreakExpected ? "UC_BREAK_EXP" : "TREND_WEAK";
        }
        
        return ind;
    }

    public Double findOpen0930(String symbol) {
        if (entityManager == null || symbol == null) return null;
        LocalTime firstSlot = firstSessionSlot();
        if (firstSlot == null) return null;
        ZonedDateTime now = clock != null ? clock.now() : ZonedDateTime.now();
        LocalDate date = now.toLocalDate();
        if (now.toLocalTime().isBefore(firstSlot)) {
            date = date.minusDays(1);
        }
        ZonedDateTime start = ZonedDateTime.of(date, firstSlot, clock.zoneId());
        int windowMinutes = settingsService.getInt("auction.detector.session-window-min", 45);
        ZonedDateTime end = start.plusMinutes(windowMinutes);
        return firstSessionPrint(normalize(symbol), start, end);
    }
    
    // --- Helpers ---

    private Double calculateEquilibrium(DepthView v, OrderAgeTracker tracker, String symbol) {
        // Collect all unique prices
        Set<Double> prices = new HashSet<>();
        if (v.getBuyLevels() != null) v.getBuyLevels().forEach(l -> prices.add(l.getPrice()));
        if (v.getSellLevels() != null) v.getSellLevels().forEach(l -> prices.add(l.getPrice()));
        if (prices.isEmpty()) return null;
        
        List<Double> sortedPrices = new ArrayList<>(prices);
        Collections.sort(sortedPrices, Collections.reverseOrder()); // Descending
        
        double bestP = 0.0;
        double maxTradable = -1.0;
        double minImbalance = Double.MAX_VALUE;
        double refPrice = v.getPrevClose() != null ? v.getPrevClose().doubleValue() : (v.getLtp() != null? v.getLtp().doubleValue() : 0.0);

        for (Double p : sortedPrices) {
            double cumBuy = getCumQty(v.getBuyLevels(), p, true, tracker, symbol);
            double cumSell = getCumQty(v.getSellLevels(), p, false, tracker, symbol);
            double tradable = Math.min(cumBuy, cumSell);
            double imbalance = Math.abs(cumBuy - cumSell);
            
            if (tradable > maxTradable) {
                maxTradable = tradable;
                bestP = p;
                minImbalance = imbalance;
            } else if (tradable == maxTradable) {
                // Tie-breaker 1: Min Imbalance
                if (imbalance < minImbalance) {
                    bestP = p;
                    minImbalance = imbalance;
                } else if (imbalance == minImbalance) {
                    // Tie-breaker 2: Closest to Reference (Prev Close)
                    if (Math.abs(p - refPrice) < Math.abs(bestP - refPrice)) {
                        bestP = p;
                    }
                }
            }
        }
        return bestP > 0 ? bestP : null;
    }
    
    private double getCumQty(List<DepthView.Level> levels, double p, boolean isBuy, OrderAgeTracker tracker, String symbol) {
        if (levels == null) return 0;
        double sum = 0;
        for (DepthView.Level l : levels) {
            boolean valid = isBuy ? (l.getPrice() >= p) : (l.getPrice() <= p);
            if (valid) {
                 double w = 1.0;
                 if (tracker != null && symbol != null) {
                     w = tracker.getWeight(symbol, l.getPrice(), isBuy);
                 }
                 sum += l.getQuantity() * w;
            }
        }
        return sum;
    }
    
    private void updatePriceHistory(String symbol, double price) {
        Deque<PricePoint> dq = priceHistory.computeIfAbsent(symbol, k -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        dq.addLast(new PricePoint(now, price));
        // Prune older than 2 * Window (10 mins) to support TrendDelta logic
        long cutoff = now - (HISTORY_WINDOW_MIN * 2 * 60 * 1000);
        while (!dq.isEmpty() && dq.getFirst().timeMillis < cutoff) {
            dq.removeFirst();
        }
    }
    
    private double calculateTrendDeltaTpm(String symbol, double tickSize) {
        Deque<PricePoint> dq = priceHistory.get(symbol);
        if (dq == null || dq.size() < 2) return 0.0;
        
        long now = System.currentTimeMillis();
        long wMillis = HISTORY_WINDOW_MIN * 60 * 1000;
        
        List<Double> currentW = new ArrayList<>();
        List<Double> prevW = new ArrayList<>();
        
        // Window 1: [Now - W, Now]
        // Window 2: [Now - 2W, Now - W]
        for (PricePoint pp : dq) {
            long age = now - pp.timeMillis;
            if (age <= wMillis) {
                currentW.add(pp.price);
            } else if (age <= 2 * wMillis) {
                prevW.add(pp.price);
            }
        }
        
        if (currentW.isEmpty() || prevW.isEmpty()) return 0.0;
        
        double medCurr = median(currentW);
        double medPrev = median(prevW);
        double delta = medCurr - medPrev;
        
        // TPM
        return delta / (tickSize * HISTORY_WINDOW_MIN);
    }
    
    private double calculateSessionDrift(String symbol) {
        if (symbol == null) return 0.0;
        String sym = normalize(symbol);
        if (sym == null) return 0.0;
        ZonedDateTime now = clock != null ? clock.now() : ZonedDateTime.now();
        SessionWindow window = resolveSessionWindow(now);
        if (window == null) return 0.0;

        SessionDriftCache cached = sessionDriftCache.get(sym);
        if (cached != null && cached.sessionStart.equals(window.currentStart)) {
            long ageSec = Duration.between(cached.computedAt, now).getSeconds();
            int refreshSec = settingsService.getInt("auction.detector.session-drift-refresh-sec", 60);
            if (ageSec < Math.max(5, refreshSec)) {
                return cached.value;
            }
        }

        Double current = medianLtp(sym, window.currentStart, window.currentEnd);
        Double previous = medianLtp(sym, window.prevStart, window.prevEnd);
        double drift = (current != null && previous != null) ? (current - previous) : 0.0;
        sessionDriftCache.put(sym, new SessionDriftCache(window.currentStart, now, drift));
        return drift;
    }

    private double median(List<Double> vals) {
        if (vals.isEmpty()) return 0.0;
        Collections.sort(vals);
        int n = vals.size();
        if (n % 2 == 1) return vals.get(n/2);
        return (vals.get((n-1)/2) + vals.get(n/2)) / 2.0;
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
            sum += w * Math.max(0, lvl != null ? lvl.getQuantity() : 0);
        }
        return sum;
    }

    private static double estimateTick(double price) {
        if (price >= 1000) return 0.5;
        if (price >= 100) return 0.05;
        return 0.01;
    }
    
    private static double resolveTickSize(DepthView v) {
        if (v.getTick() != null && v.getTick() > 0) return v.getTick();
        if (v.getLtp() != null) return estimateTick(v.getLtp().doubleValue());
        return 0.05;
    }

    private LocalTime firstSessionSlot() {
        List<LocalTime> slots = sessionSlots();
        if (slots == null || slots.isEmpty()) return null;
        return slots.stream().min(LocalTime::compareTo).orElse(null);
    }

    private SessionWindow resolveSessionWindow(ZonedDateTime now) {
        List<LocalTime> slots = sessionSlots();
        if (slots.isEmpty()) return null;

        LocalDate date = now.toLocalDate();
        LocalTime nowTime = now.toLocalTime();
        LocalTime currentSlot = null;
        for (LocalTime slot : slots) {
            if (!slot.isAfter(nowTime)) currentSlot = slot;
        }
        if (currentSlot == null) {
            currentSlot = slots.get(slots.size() - 1);
            date = date.minusDays(1);
        }

        ZonedDateTime currentStart = ZonedDateTime.of(date, currentSlot, clock.zoneId());
        int windowMinutes = settingsService.getInt("auction.detector.session-window-min", 45);
        ZonedDateTime currentEnd = currentStart.plusMinutes(windowMinutes);

        LocalDate prevDate = currentStart.toLocalDate();
        LocalTime prevSlot = null;
        for (int i = slots.size() - 1; i >= 0; i--) {
            if (slots.get(i).isBefore(currentSlot)) {
                prevSlot = slots.get(i);
                break;
            }
        }
        if (prevSlot == null) {
            prevSlot = slots.get(slots.size() - 1);
            prevDate = prevDate.minusDays(1);
        }

        ZonedDateTime prevStart = ZonedDateTime.of(prevDate, prevSlot, clock.zoneId());
        ZonedDateTime prevEnd = prevStart.plusMinutes(windowMinutes);
        return new SessionWindow(currentStart, currentEnd, prevStart, prevEnd);
    }

    private List<LocalTime> sessionSlots() {
        List<LocalTime> configured = (slotConfig != null) ? slotConfig.orderedTimes() : List.of();
        if (configured != null && !configured.isEmpty()) return configured;
        return SessionSlot.ordered().stream().map(SessionSlot::getTime).collect(Collectors.toList());
    }

    private Double medianLtp(String symbol, ZonedDateTime start, ZonedDateTime end) {
        if (entityManager == null || symbol == null || start == null || end == null) return null;
        String sql = "select percentile_cont(0.5) within group (order by ltp) " +
                "from market_snapshots " +
                "where symbol = :sym and captured_at >= :start and captured_at < :end and ltp is not null";
        try {
            Query query = entityManager.createNativeQuery(sql);
            query.setParameter("sym", symbol);
            query.setParameter("start", start);
            query.setParameter("end", end);
            Object result = query.getSingleResult();
            if (result == null) return null;
            if (result instanceof Number n) return n.doubleValue();
            return Double.valueOf(result.toString());
        } catch (Exception e) {
            log.debug("Session drift query failed for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    private Double firstSessionPrint(String symbol, ZonedDateTime start, ZonedDateTime end) {
        if (entityManager == null || symbol == null || start == null || end == null) return null;
        String sql = "select coalesce(ltp, open_price) " +
                "from market_snapshots " +
                "where symbol = :sym and captured_at >= :start and captured_at < :end " +
                "and (ltp is not null or open_price is not null) " +
                "order by captured_at asc";
        try {
            Query query = entityManager.createNativeQuery(sql);
            query.setParameter("sym", symbol);
            query.setParameter("start", start);
            query.setParameter("end", end);
            query.setMaxResults(1);
            Object result = query.getSingleResult();
            if (result == null) return null;
            if (result instanceof Number n) return n.doubleValue();
            return Double.valueOf(result.toString());
        } catch (Exception e) {
            log.debug("Open0930 query failed for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    // --- Hazard Zone & Spoofing Logic ---

    // Rolling max of Top-3 Buy Qty for Phantom Wall detection
    private final Map<String, Long> rollingMaxBuyQty = new ConcurrentHashMap<>();

    public boolean detectPhantomWall(DepthView v) {
        if (v == null || v.getBuyLevels() == null) return false;
        
        long currentTop3 = 0;
        for (int i = 0; i < Math.min(3, v.getBuyLevels().size()); i++) {
            currentTop3 += v.getBuyLevels().get(i).getQuantity();
        }
        
        String sym = normalize(v.getTradingsymbol());
        Long max = rollingMaxBuyQty.getOrDefault(sym, 0L);
        
        // Update rolling max (decay or reset logic needed? For simplicity, we just track max seen in session or window)
        // Ideally we need a time-windowed max. For now, let's use a simple decayed max approach or just max.
        // Let's implement a simple updates: if current > max, max = current. 
        // If current < max, checking for drop.
        // We need to reset this occasionally. Let's rely on EsmExecutionService to manage session lifecycle or just use a simple decay.
        // A simple "reset on session start" is best, but here we don't have session ctx.
        // We'll use a lazy decay: on every check, if current < max, we don't reduce max. 
        // But if current is significantly lower, it triggers.
        // To prevent "stale high" from forever ago, we can decay max slightly on every update if current < max?
        // Better: EsmExecutionService calls a reset method.
        
        if (currentTop3 > max) {
            rollingMaxBuyQty.put(sym, currentTop3);
            return false;
        }
        
        // Drop Check: > 40% drop
        if (max > 0 && currentTop3 < (max * 0.6)) {
             // Phantom Wall detected!
             // But valid only if max was "substantial" (e.g > 1000 qty).
             return max > 500;
        }
        
        return false;
    }
    
    public void resetPhantomWallStats(String symbol) {
        rollingMaxBuyQty.remove(normalize(symbol));
    }

    public boolean getHazardRisk(DepthView v, long myQty) {
        if (v == null) return false;
        long totalBuy = v.getBuyQuantity();
        long totalSell = v.getSellQuantity();
        
        // Formula: ThreatLevel = TotalSellQty + MyHoldings + (0.05 * TotalBuyQty)
        // If TotalBuyQty <= ThreatLevel -> Risk
        
        double margin = 0.05 * totalBuy;
        double threatLevel = totalSell + myQty + margin;
        
        return totalBuy <= threatLevel;
    }

    private static Double round(Double v, int scale) {
        if (v == null) return null;
        double factor = Math.pow(10, scale);
        return Math.round(v * factor) / factor;
    }
    
    private boolean detectSpoofing(DepthView v) {
         return detectPhantomWall(v);
    }
    
    private long sumQty(List<DepthView.Level> lvls) {
        if (lvls == null) return 0;
        return lvls.stream().mapToLong(DepthView.Level::getQuantity).sum();
    }
}
