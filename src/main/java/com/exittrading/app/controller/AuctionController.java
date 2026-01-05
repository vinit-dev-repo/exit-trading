package com.exittrading.app.controller;

import com.exittrading.app.dto.DepthView;
import com.exittrading.app.dto.DetectionSummary;
import com.exittrading.app.dto.EsmFlagSummary;
import com.exittrading.app.service.core.AdminService;
import com.exittrading.app.service.core.DepthService;
import com.exittrading.app.service.core.DepthStreamService;
import com.exittrading.app.service.auction.AuctionAnalysisService;
import com.exittrading.app.service.auction.OrderAgeTracker;
import com.exittrading.app.service.util.DepthViewUtil;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Controller for auction analysis endpoints.
 * Provides summaries of market depth analysis for spoofing/pressure detection.
 */
@RestController
@RequestMapping("/api/auction")
public class AuctionController {

    private final AdminService adminService;
    private final DepthService depthService;
    private final DepthStreamService depthStreamService;
    private final AuctionAnalysisService analysisService;
    private final OrderAgeTracker orderAgeTracker;

    public AuctionController(AdminService adminService,
                             DepthService depthService,
                             DepthStreamService depthStreamService,
                             AuctionAnalysisService analysisService,
                             OrderAgeTracker orderAgeTracker) {
        this.adminService = adminService;
        this.depthService = depthService;
        this.depthStreamService = depthStreamService;
        this.analysisService = analysisService;
        this.orderAgeTracker = orderAgeTracker;
    }

    @GetMapping("/summary/{username}")
    public List<DetectionSummary> summary(@PathVariable String username) {
        return adminService.findOptionalByUsername(username)
                .map(user -> {
                    List<DepthView> latest = resolveDepthViews(user);
                    return analysisService.summarize(latest);
                })
                .orElseGet(java.util.List::of);
    }

    @GetMapping("/flags/{username}")
    public List<EsmFlagSummary> flags(@PathVariable String username) {
        return adminService.findOptionalByUsername(username)
                .map(user -> {
                    List<DepthView> latest = resolveDepthViews(user);
                    return latest.stream()
                            .map(this::toFlagSummary)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());
                })
                .orElseGet(java.util.List::of);
    }

    private List<DepthView> resolveDepthViews(com.exittrading.app.domain.UserAccount user) {
        List<DepthView> fromStream = depthStreamService.snapshotFor(user);
        boolean needsFallback = (fromStream == null || fromStream.isEmpty());
        if (!needsFallback) {
            Set<String> expected = holdingSymbols(user);
            Set<String> received = fromStream.stream()
                    .map(DepthView::getTradingsymbol)
                    .map(AuctionController::normalize)
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.toSet());
            if (!expected.isEmpty() && !received.containsAll(expected)) {
                needsFallback = true;
            }
            if (!needsFallback && fromStream.stream().anyMatch(DepthViewUtil::needsEnrichment)) {
                needsFallback = true;
            }
        }
        List<DepthView> fallback = needsFallback ? depthService.latestOrLive(user) : null;
        return mergeDepth(fromStream, fallback);
    }

    private List<DepthView> mergeDepth(List<DepthView> primary, List<DepthView> fallback) {
        Map<String, DepthView> merged = new LinkedHashMap<>();
        if (primary != null) {
            for (DepthView v : primary) {
                String key = normalize(v != null ? v.getTradingsymbol() : null);
                if (key != null) merged.put(key, v);
            }
        }
        if (fallback != null) {
            for (DepthView v : fallback) {
                String key = normalize(v != null ? v.getTradingsymbol() : null);
                if (key != null) {
                    DepthView existing = merged.get(key);
                    if (existing == null) {
                        merged.put(key, v);
                    } else {
                        DepthViewUtil.mergeMissing(existing, v);
                        merged.put(key, existing);
                    }
                }
            }
        }
        return new ArrayList<>(merged.values());
    }

    private Set<String> holdingSymbols(com.exittrading.app.domain.UserAccount user) {
        if (user == null || user.getHoldings() == null) return Set.of();
        return user.getHoldings().stream()
                .map(s -> s != null ? s.split("\\|")[0] : null)
                .map(AuctionController::normalize)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.toSet());
    }

    private EsmFlagSummary toFlagSummary(DepthView depth) {
        if (depth == null || depth.getTradingsymbol() == null) return null;
        String symbol = normalize(depth.getTradingsymbol());
        if (symbol == null) return null;

        if (depth.getBuyLevels() != null && !depth.getBuyLevels().isEmpty()
                && depth.getSellLevels() != null && !depth.getSellLevels().isEmpty()) {
            orderAgeTracker.update(symbol, depth);
        }
        double prevClose = depth.getPrevClose() != null ? depth.getPrevClose().doubleValue() : 0.0;
        Double open0930 = analysisService.findOpen0930(symbol);
        if (open0930 == null && depth.getOpen() != null) {
            open0930 = depth.getOpen().doubleValue();
        }

        AuctionAnalysisService.EsmIndicators indicators =
                analysisService.calculateIndicators(depth, orderAgeTracker, prevClose, open0930, null);

        EsmFlagSummary out = new EsmFlagSummary();
        out.symbol = symbol;
        out.lastTradedPrice = depth.getLtp() != null ? depth.getLtp().doubleValue() : null;
        out.openAtSessionStart = open0930;
        out.previousClose = depth.getPrevClose() != null ? depth.getPrevClose().doubleValue() : null;
        out.upperCircuit = indicators.uc != null ? indicators.uc
                : (depth.getUpperCircuit() != null ? depth.getUpperCircuit().doubleValue() : null);
        out.limitPrice = indicators.pLimit;
        out.equilibriumPriceRaw = indicators.pRaw;
        out.equilibriumPriceRobust = indicators.pRobust;
        out.orderBookImbalance = indicators.imbalance;
        out.trendDeltaTpm = indicators.trendDeltaTpm;
        out.sessionDrift = indicators.sessionDrift;
        out.downsideGateActive = indicators.dDown;
        out.downsideGateReason = indicators.reason;
        out.upperCircuitLock = indicators.ucLock;
        out.upperCircuitBreakExpected = indicators.ucBreakExpected;
        return out;
    }

    private static String normalize(String s) {
        if (s == null) return null;
        int idx = s.indexOf(":");
        return idx > -1 ? s.substring(idx + 1) : s;
    }
}
