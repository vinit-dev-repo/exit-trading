package com.exittrading.app.service;

import com.exittrading.app.domain.DepthSnapshot;
import com.exittrading.app.domain.TradingSchedule;
import com.exittrading.app.domain.UserAccount;
import com.exittrading.app.dto.DepthView;
import com.exittrading.app.repository.DepthSnapshotRepository;
import com.exittrading.app.service.InstrumentService;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class DepthService {

    private static final Logger tlog = LoggerFactory.getLogger("ticker");

    private final DepthSnapshotRepository repository;
    private final KiteGateway gateway;
    private final InstrumentService instrumentService;

    public DepthService(DepthSnapshotRepository repository, KiteGateway gateway, InstrumentService instrumentService) {
        this.repository = repository;
        this.gateway = gateway;
        this.instrumentService = instrumentService;
    }

    public DepthView captureDepth(TradingSchedule schedule) {
        try {
            return gateway.fetchDepth(schedule).get(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            org.slf4j.LoggerFactory.getLogger(DepthService.class)
                    .warn("Depth capture timed out for {}", schedule != null ? schedule.getTradingsymbol() : "n/a");
            return null;
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(DepthService.class)
                    .warn("Depth capture failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Ad-hoc depth fetch for diagnostics. Accepts either EXCH:SYMBOL or plain symbol.
     * If token is provided, it is preferred by the gateway.
     */
    public DepthView fetchOne(String instrumentOrSymbol, String token) {
        try {
            return gateway.fetchDepth(instrumentOrSymbol, token).get(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            org.slf4j.LoggerFactory.getLogger(DepthService.class)
                    .warn("Depth fetch timed out for {}", instrumentOrSymbol);
            return null;
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(DepthService.class)
                    .warn("Depth fetch failed: {}", e.getMessage());
            return null;
        }
    }

    @Transactional
    public void persistDepth(UserAccount user, DepthView view) {
        if (view == null) {
            return;
        }
        DepthSnapshot snapshot = new DepthSnapshot();
        snapshot.setUser(user);
        snapshot.setTradingsymbol(view.getTradingsymbol());
        snapshot.setBuyQuantity(view.getBuyQuantity());
        snapshot.setSellQuantity(view.getSellQuantity());
        snapshot.setLtp(view.getLtp());
        snapshot.setCapturedAt(view.getCapturedAt());
        repository.save(snapshot);
        // Log readable ticker line for persisted snapshot as well
        logTicker(view);
    }

    public List<DepthView> latest(UserAccount user) {
        return repository.findTop10ByUserOrderByCapturedAtDesc(user)
                .stream()
                .map(snapshot -> {
                    DepthView view = new DepthView();
                    view.setTradingsymbol(snapshot.getTradingsymbol());
                    view.setBuyQuantity(snapshot.getBuyQuantity());
                    view.setSellQuantity(snapshot.getSellQuantity());
                    view.setLtp(snapshot.getLtp());
                    view.setCapturedAt(snapshot.getCapturedAt());
                    try {
                        String sym = snapshot.getTradingsymbol();
                        String exch = null;
                        if (sym != null && sym.contains(":")) {
                            int idx = sym.indexOf(':');
                            exch = sym.substring(0, idx);
                            sym = sym.substring(idx + 1);
                        }
                        InstrumentService.InstrumentMeta meta = instrumentService.find(exch, sym, null);
                        if (meta != null) {
                            view.setTick(meta.tickSize);
                            if (meta.lowerCircuit != null && view.getLowerCircuit() == null) view.setLowerCircuit(java.math.BigDecimal.valueOf(meta.lowerCircuit));
                            if (meta.upperCircuit != null && view.getUpperCircuit() == null) view.setUpperCircuit(java.math.BigDecimal.valueOf(meta.upperCircuit));
                        }
                    } catch (Exception ignored) {}
                    return view;
                }).collect(Collectors.toList());
    }

    /**
     * Returns latest persisted snapshots; if none exist yet for the user,
     * fetch a small live sample using the configured gateway based on the
     * user's holdings. This makes the UI useful even before any orders run.
     */
    public List<DepthView> latestOrLive(UserAccount user) {
        List<DepthView> persisted = latest(user);
        if (!persisted.isEmpty()) {
            // Also mirror to ticker log for visibility
            persisted.forEach(this::logTicker);
            return persisted;
        }
        // Fallback: fetch up to 5 live depths from holdings (resilient to per-symbol failures)
        java.util.List<DepthView> result = new java.util.ArrayList<>();
        user.getHoldings().stream()
                .filter(sym -> sym != null && !sym.isBlank())
                .limit(5)
                .forEach(sym -> {
                    try {
                        String main = sym;
                        int pipe = main.indexOf('|');
                        if (pipe > -1) {
                            main = main.substring(0, pipe);
                        }
                        // Extract token if present as 8th segment
                        String token = null;
                        String[] parts = sym.split("\\|");
                        if (parts.length >= 8) {
                            token = parts[7] != null && !parts[7].isBlank() ? parts[7].trim() : null;
                        }
                        String instrument = main; // keep exchange prefix if present
                        DepthView v = null;
                        try {
                            v = gateway.fetchDepth(instrument, token).get(3, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (Exception ignore) {
                            // ignore symbol-level failures; continue others
                        }
                        if (v != null) {
                            result.add(v);
                            logTicker(v);
                        }
                    } catch (Exception ex) { /* skip */ }
                });
        if (result.isEmpty()) {
            try { tlog.info("{} | ts=- | ltp=- | buyQty=0 | sellQty=0 | bid1=- | ask1=-", user.getUsername()); } catch (Exception ignored) {}
        }
        return result;
    }

    private void logTicker(DepthView v) {
        try {
            if (v == null) return;
            String sym = v.getTradingsymbol();
            if (sym == null) sym = "-";
            String ts = v.getCapturedAt() != null ? v.getCapturedAt().toLocalTime().toString() : "-";
            String ltp = v.getLtp() != null ? v.getLtp().toPlainString() : "-";
            String bid1 = levelStrSafe(v.getBuyLevels());
            String ask1 = levelStrSafe(v.getSellLevels());
            tlog.info("{} | ts={} | ltp={} | buyQty={} | sellQty={} | bid1={} | ask1={}",
                    sym, ts, ltp, v.getBuyQuantity(), v.getSellQuantity(), bid1, ask1);
        } catch (Exception ignored) {}
    }

    private String levelStrSafe(java.util.List<DepthView.Level> lvls) {
        if (lvls == null || lvls.isEmpty()) return "-";
        DepthView.Level l = lvls.get(0);
        return String.valueOf(l.getPrice()) + "×" + l.getQuantity() + "(" + l.getOrders() + ")";
    }
}
