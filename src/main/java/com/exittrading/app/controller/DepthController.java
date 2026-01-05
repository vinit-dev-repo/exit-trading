package com.exittrading.app.controller;

import com.exittrading.app.dto.DepthView;
import com.exittrading.app.service.core.AdminService;
import com.exittrading.app.service.core.DepthStreamService;
import com.exittrading.app.service.core.DepthService;
import com.exittrading.app.service.util.DepthViewUtil;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Controller for retrieving market depth data.
 * Merges persisted snapshots with live data from the depth stream.
 */
@RestController
@RequestMapping("/api/depth")
public class DepthController {

    private final DepthService depthService;
    private final AdminService adminService;
    private final DepthStreamService depthStreamService;

    public DepthController(DepthService depthService, AdminService adminService, DepthStreamService depthStreamService) {
        this.depthService = depthService;
        this.adminService = adminService;
        this.depthStreamService = depthStreamService;
    }

    @GetMapping("/{username}")
    public List<DepthView> latestDepth(@PathVariable String username) {
        return adminService.findOptionalByUsername(username)
                .map(user -> {
                    List<DepthView> fromStream = depthStreamService.snapshotFor(user);
                    boolean needsFallback = (fromStream == null || fromStream.isEmpty());
                    if (!needsFallback) {
                        Set<String> expected = holdingSymbols(user);
                        Set<String> received = fromStream.stream()
                                .map(DepthView::getTradingsymbol)
                                .map(DepthController::normalize)
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
                })
                .orElseGet(java.util.List::of);
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
                .map(DepthController::normalize)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.toSet());
    }

    private static String normalize(String s) {
        if (s == null) return null;
        int idx = s.indexOf(':');
        return idx > -1 ? s.substring(idx + 1) : s;
    }

    // Simple diagnostics endpoint to validate parsing end-to-end.
    // Example: /api/depth/test?instrument=NSE:APOLLOTYRE or /api/depth/test?token=24507906
    @GetMapping("/test")
    public DepthView testDepth(@RequestParam(name = "instrument", required = false) String instrument,
                               @RequestParam(name = "token", required = false) String token) {
        String key = (instrument != null && !instrument.isBlank()) ? instrument : (token != null ? token : null);
        if (key == null) {
            // Provide a sensible default as requested
            instrument = "NSE:APOLLOTYRE";
        }
        return depthService.fetchOne(instrument != null ? instrument : token, token);
    }
}
