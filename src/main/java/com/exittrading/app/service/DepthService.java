package com.exittrading.app.service;

import com.exittrading.app.domain.DepthSnapshot;
import com.exittrading.app.domain.TradingSchedule;
import com.exittrading.app.domain.UserAccount;
import com.exittrading.app.dto.DepthView;
import com.exittrading.app.repository.DepthSnapshotRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class DepthService {

    private final DepthSnapshotRepository repository;
    private final KiteGateway gateway;

    public DepthService(DepthSnapshotRepository repository, KiteGateway gateway) {
        this.repository = repository;
        this.gateway = gateway;
    }

    public DepthView captureDepth(TradingSchedule schedule) {
        return gateway.fetchDepth(schedule).join();
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
            return persisted;
        }
        // Fallback: fetch up to 5 live depths from holdings
        return user.getHoldings().stream()
                .filter(sym -> sym != null && !sym.isBlank())
                .limit(5)
                .map(sym -> {
                    String symbolOnly = sym;
                    int idx = sym.indexOf(':');
                    if (idx > -1 && idx + 1 < sym.length()) {
                        symbolOnly = sym.substring(idx + 1);
                    }
                    return gateway.fetchDepth(symbolOnly, null);
                })
                .map(java.util.concurrent.CompletableFuture::join)
                .collect(Collectors.toList());
    }
}
