package com.exittrading.app.service.core;

import com.exittrading.app.domain.TradingSchedule;
import com.exittrading.app.domain.UserAccount;
import com.exittrading.app.dto.DepthView;
import com.exittrading.app.logging.ScripLogFormatter;
import com.exittrading.app.service.core.KiteGateway;
import com.exittrading.app.service.core.InstrumentService;
import com.exittrading.app.service.util.UserAccountUtil;
import com.exittrading.app.service.util.DepthViewUtil;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

/**
 * Service for fetching and persisting market depth (Level 2) data.
 * Handles interactions with the Kite Gateway and database persistence.
 * Centralizes suppression logic and persistence pipeline.
 */
@Service
public class DepthService {

    private static final Logger tlog = LoggerFactory.getLogger("ticker");

    private final com.exittrading.app.repository.MarketSnapshotRepository repository;
    private final KiteGateway gateway;
    private final InstrumentService instrumentService;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;
    private final SettingsService settingsService;
    private final Map<Long, DepthView> lastKnownByToken = new ConcurrentHashMap<>();

    public DepthService(com.exittrading.app.repository.MarketSnapshotRepository repository, KiteGateway gateway,
                        InstrumentService instrumentService, EntityManager entityManager, ObjectMapper objectMapper,
                        SettingsService settingsService) {
        this.repository = repository;
        this.gateway = gateway;
        this.instrumentService = instrumentService;
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
        this.settingsService = settingsService;
    }

    public DepthView captureDepth(TradingSchedule schedule) {
        try {
            return gateway.fetchDepth(schedule).get(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            tlog.debug("Depth capture failed for {}: {}", schedule != null ? schedule.getTradingsymbol() : "unknown", e.getMessage());
            return null;
        }
    }

    public DepthView fetchOne(String instrumentOrSymbol, String token) {
        try {
            DepthView view = gateway.fetchDepth(instrumentOrSymbol, token).get(3, java.util.concurrent.TimeUnit.SECONDS);
            Long tokenVal = parseToken(token);
            if (view != null && tokenVal != null && tokenVal > 0) {
                applyLastKnown(tokenVal, view);
            }
            return view;
        } catch (Exception e) {
            tlog.debug("Depth fetch failed for {}: {}", instrumentOrSymbol, e.getMessage());
            return null;
        }
    }

    /**
     * Single entry point for persisting depth snapshots from Streams or REST.
     * Applies suppression logic using sell-pressure checks unless forced.
     * @param view The depth view to persist
     * @param token The instrument token
     * @param force If true, bypasses suppression (e.g. Manual Capture)
     */
    @Transactional
    public void persistDepth(DepthView view, String token, boolean force) {
        if (view == null) return;
        
        // Phase 4: Data Integrity - Reject snapshots with missing Price (LTP)
        if (view.getLtp() == null) {
            return;
        }
        
        // Suppression Logic: Only suppress if NOT forced
        if (!force && shouldSuppress(view)) {
            return;
        }
        
        ZonedDateTime capturedAt = view.getCapturedAt() != null ? view.getCapturedAt() : ZonedDateTime.now();
        Long tokenVal = 0L;
        if (token != null) {
            try { tokenVal = Long.parseLong(token); } catch(Exception e) {}
        }
        
        // Fix for missing Symbol: Try to resolve from InstrumentService if missing
        if (view.getTradingsymbol() == null && tokenVal != 0L) {
             try {
                 InstrumentService.InstrumentMeta meta = instrumentService.findByToken(String.valueOf(tokenVal));
                 if (meta != null && meta.symbol != null) {
                     view.setTradingsymbol(meta.symbol);
                 }
             } catch (Exception e) {
                 tlog.debug("Failed to resolve symbol for token {}: {}", tokenVal, e.getMessage());
             }
        }

        if (tokenVal == 0L) {
             // Try check instrument service using symbol
             try {
                 InstrumentService.InstrumentMeta meta = instrumentService.find(null, view.getTradingsymbol(), null);
                 if (meta != null && meta.token != null) tokenVal = Long.parseLong(meta.token);
             } catch (Exception e) {
                 tlog.debug("Failed to resolve token for symbol {}: {}", view.getTradingsymbol(), e.getMessage());
             }
        }
        
        // Final Check: If symbol is still null, we cannot persist due to NOT NULL constraint.
        if (view.getTradingsymbol() == null) {
            tlog.warn("Skipping persistence for token {} as Symbol is missing/unresolvable", tokenVal);
            instrumentService.registerUnresolved(tokenVal);
            return;
        }

        if (tokenVal > 0) {
            applyLastKnown(tokenVal, view);
        }

        // Native Upsert for Performance
        String sql = "INSERT INTO market_snapshots (captured_at, token, symbol, ltp, ltq, ltt, volume, " +
                     "open_price, high_price, low_price, prev_close, lower_circuit, upper_circuit, bids, asks) " +
                     "VALUES (:ts, :tok, :sym, :ltp, :ltq, :ltt, :vol, :op, :hi, :lo, :pc, :lc, :uc, CAST(:bids AS jsonb), CAST(:asks AS jsonb)) " +
                     "ON CONFLICT (captured_at, token) DO UPDATE SET " +
                     "ltp = EXCLUDED.ltp, volume = EXCLUDED.volume, ltq = EXCLUDED.ltq, ltt = EXCLUDED.ltt, " +
                     "bids = EXCLUDED.bids, asks = EXCLUDED.asks, high_price = EXCLUDED.high_price, low_price = EXCLUDED.low_price";

        try {
            Query query = entityManager.createNativeQuery(sql);
            query.setParameter("ts", capturedAt);
            query.setParameter("tok", tokenVal);
            query.setParameter("sym", view.getTradingsymbol());
            query.setParameter("ltp", view.getLtp() != null ? view.getLtp().doubleValue() : null);
            query.setParameter("ltq", view.getLtq());
            query.setParameter("ltt", view.getLtt());
            query.setParameter("vol", view.getVolume());
            query.setParameter("op", view.getOpen() != null ? view.getOpen().doubleValue() : null);
            query.setParameter("hi", view.getHigh() != null ? view.getHigh().doubleValue() : null);
            query.setParameter("lo", view.getLow() != null ? view.getLow().doubleValue() : null);
            query.setParameter("pc", view.getPrevClose() != null ? view.getPrevClose().doubleValue() : null);
            query.setParameter("lc", view.getLowerCircuit() != null ? view.getLowerCircuit().doubleValue() : null);
            query.setParameter("uc", view.getUpperCircuit() != null ? view.getUpperCircuit().doubleValue() : null);

            List<Map<String,Object>> bidsList = null;
            if (view.getBuyLevels() != null) {
                 bidsList = view.getBuyLevels().stream().map(l -> java.util.Map.<String,Object>of("price", l.getPrice(), "quantity", l.getQuantity(), "orders", l.getOrders())).collect(Collectors.toList());
            }
            List<Map<String,Object>> asksList = null;
            if (view.getSellLevels() != null) {
                 asksList = view.getSellLevels().stream().map(l -> java.util.Map.<String,Object>of("price", l.getPrice(), "quantity", l.getQuantity(), "orders", l.getOrders())).collect(Collectors.toList());
            }

            query.setParameter("bids", bidsList != null ? objectMapper.writeValueAsString(bidsList) : "[]");
            query.setParameter("asks", asksList != null ? objectMapper.writeValueAsString(asksList) : "[]");

            query.executeUpdate();
            logTicker(view, null, String.valueOf(tokenVal));

        } catch (Exception e) {
            tlog.error("Failed to persist depth for token {}: {}", tokenVal, e.getMessage());
        }
    }
    
    public boolean shouldSuppress(DepthView view) {
        long bQ = view.getBuyQuantity();
        long sQ = view.getSellQuantity();
        long total = bQ + sQ;
        if (total > 0) {
            double sellPressure = (double) sQ / total * 100.0;
            try {
                int suppressThreshold = settingsService.getInt("market.depth.suppress.sell-pressure-threshold", 95);
                String suppressStartTime = settingsService.getString("market.depth.suppress.start-time", "10:30");
                boolean downtrendSuppressionEnabled = settingsService.getBoolean("market.depth.suppress.downtrend-enabled", false);
                double downtrendSellPressureThreshold = settingsService.getDouble("market.depth.suppress.downtrend-sell-pressure-threshold", 40);
                double downtrendLtpDropBps = settingsService.getDouble("market.depth.suppress.downtrend-ltp-drop-bps", 0);

                LocalTime start = LocalTime.parse(suppressStartTime);
                boolean afterStart = ZonedDateTime.now().toLocalTime().isAfter(start);
                if (sellPressure > suppressThreshold && afterStart) {
                    return true;
                }
                if (downtrendSuppressionEnabled
                        && sellPressure >= downtrendSellPressureThreshold
                        && afterStart
                        && isDowntrend(view, downtrendLtpDropBps)) {
                    return true;
                }
            } catch (Exception e) {
                tlog.debug("Suppression time parse failed: {}", e.getMessage());
            }
        }
        return false;
    }

    private boolean isDowntrend(DepthView view, double downtrendLtpDropBps) {
        if (view == null) return false;
        Double ltp = view.getLtp() != null ? view.getLtp().doubleValue() : null;
        Double prevClose = view.getPrevClose() != null ? view.getPrevClose().doubleValue() : null;
        Double open = view.getOpen() != null ? view.getOpen().doubleValue() : null;
        if (ltp == null || ltp <= 0) return false;
        Double base = (prevClose != null && prevClose > 0) ? prevClose : (open != null && open > 0 ? open : null);
        if (base == null) return false;
        double dropBps = ((base - ltp) / base) * 10000.0;
        return dropBps >= downtrendLtpDropBps;
    }

    public List<DepthView> latest(UserAccount user) {
        Set<Long> tokens = UserAccountUtil.extractHoldingTokens(user);
        if (tokens.isEmpty()) return List.of();

        return repository.findLatestByTokenIn(tokens)
                .stream()
                .map(snapshot -> {
                    DepthView view = new DepthView();
                    view.setTradingsymbol(snapshot.getSymbol());
                    view.setLtp(snapshot.getLtp() != null ? java.math.BigDecimal.valueOf(snapshot.getLtp()) : null);
                    view.setCapturedAt(snapshot.getCapturedAt());
                    view.setVolume(snapshot.getVolume());
                    view.setOpen(snapshot.getOpenPrice() != null ? BigDecimal.valueOf(snapshot.getOpenPrice()) : null);
                    view.setHigh(snapshot.getHighPrice() != null ? BigDecimal.valueOf(snapshot.getHighPrice()) : null);
                    view.setLow(snapshot.getLowPrice() != null ? BigDecimal.valueOf(snapshot.getLowPrice()) : null);
                    view.setPrevClose(snapshot.getPrevClose() != null ? BigDecimal.valueOf(snapshot.getPrevClose()) : null);
                    view.setLowerCircuit(snapshot.getLowerCircuit() != null ? BigDecimal.valueOf(snapshot.getLowerCircuit()) : null);
                    view.setUpperCircuit(snapshot.getUpperCircuit() != null ? BigDecimal.valueOf(snapshot.getUpperCircuit()) : null);
                    view.setLtq(snapshot.getLtq());
                    view.setLtt(snapshot.getLtt());
                    List<DepthView.Level> buyLevels = mapLevels(snapshot.getBids());
                    List<DepthView.Level> sellLevels = mapLevels(snapshot.getAsks());
                    view.setBuyLevels(buyLevels);
                    view.setSellLevels(sellLevels);
                    view.setBuyQuantity(sumQty(buyLevels));
                    view.setSellQuantity(sumQty(sellLevels));
                    return view;
                }).collect(Collectors.toList());
    }

    private static List<DepthView.Level> mapLevels(List<Map<String, Object>> levels) {
        if (levels == null || levels.isEmpty()) return List.of();
        List<DepthView.Level> out = new ArrayList<>(levels.size());
        for (Map<String, Object> lvl : levels) {
            if (lvl == null) continue;
            double price = doubleVal(lvl.get("price"));
            int qty = intVal(lvl.get("quantity"));
            int orders = intVal(lvl.get("orders"));
            out.add(new DepthView.Level(qty, price, orders));
        }
        return out;
    }

    private static long sumQty(List<DepthView.Level> levels) {
        if (levels == null || levels.isEmpty()) return 0L;
        long sum = 0L;
        for (DepthView.Level lvl : levels) {
            if (lvl == null) continue;
            sum += Math.max(0, lvl.getQuantity());
        }
        return sum;
    }

    private static int intVal(Object value) {
        if (value == null) return 0;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (Exception e) {
            return 0;
        }
    }

    private static double doubleVal(Object value) {
        if (value == null) return 0.0;
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (Exception e) {
            return 0.0;
        }
    }

    private void applyLastKnown(Long tokenVal, DepthView view) {
        if (tokenVal == null || tokenVal <= 0 || view == null) return;
        DepthView prev = lastKnownByToken.get(tokenVal);
        if (prev != null) {
            DepthViewUtil.mergeMissing(view, prev);
        }
        lastKnownByToken.put(tokenVal, view);
    }

    private static Long parseToken(String token) {
        if (token == null || token.isBlank()) return null;
        try {
            return Long.parseLong(token);
        } catch (Exception e) {
            return null;
        }
    }

    public List<DepthView> latestOrLive(UserAccount user) {
        // Reuse logic but cleaned up? 
        // Keeping it similar to before but utilizing Gateway abstraction 
        // This method merges DB persistence with LIVE fetch if stale.
        // For brevity in this refactor, I will keep the core logic but ensure type safety.
        // Also it uses UserAccountUtil now.
        List<DepthView> persisted = latest(user);
        Map<String, DepthView> merged = new java.util.LinkedHashMap<>();
        persisted.forEach(v -> {
            if (v != null && v.getTradingsymbol() != null) merged.put(v.getTradingsymbol(), v);
        });

        ZonedDateTime cutoff = ZonedDateTime.now().minusSeconds(30);

        user.getHoldings().stream()
                .filter(sym -> sym != null && !sym.isBlank())
                .limit(8)
                .forEach(sym -> {
                    try {
                        Long token = UserAccountUtil.parseToken(sym); // returns Long
                        String symbol = UserAccountUtil.parseSymbol(sym); // returns String
                        String keySymbol = symbol != null ? symbol : sym; // fallback
                        
                        // Parse instrument for gateway (EXCH:SYMBOL) logic if needed
                        String instrument = sym.contains("|") ? sym.substring(0, sym.indexOf('|')) : sym;

                        DepthView existing = merged.get(keySymbol);
                        boolean stale = existing == null
                                || existing.getCapturedAt() == null
                                || existing.getCapturedAt().isBefore(cutoff);
             
                    if (stale) {
                        DepthView live = gateway.fetchDepth(instrument, token != null ? String.valueOf(token) : null).get(3, java.util.concurrent.TimeUnit.SECONDS);
                        if (live != null) {
                            if (existing != null) {
                                DepthViewUtil.mergeMissing(live, existing);
                            }
                            merged.put(keySymbol, live);
                        }
                    }
                } catch (Exception ex) {
                    tlog.debug("Live depth fetch failed for {}: {}", sym, ex.getMessage());
                }
            });
        return new java.util.ArrayList<>(merged.values());
    }

    private void logTicker(DepthView v, String instrument, String tokenOverride) {
        try {
            if (v == null) return;
            String sym = (instrument != null && !instrument.isBlank()) ? instrument : (v.getTradingsymbol() != null ? v.getTradingsymbol() : "-");
            String token = (tokenOverride != null && !tokenOverride.isBlank()) ? tokenOverride : "-"; 
            tlog.debug(ScripLogFormatter.format(sym, token, v));
        } catch (Exception e) {
            tlog.debug("Ticker log failed: {}", e.getMessage());
        }
    }
}
