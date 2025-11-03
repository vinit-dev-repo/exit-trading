package com.exittrading.app.controller;

import com.exittrading.app.dto.DepthView;
import com.exittrading.app.service.AdminService;
import com.exittrading.app.service.DepthStreamService;
import org.springframework.web.bind.annotation.RequestParam;
import com.exittrading.app.service.DepthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
                    // Prefer stream snapshot for timeliness
                    List<DepthView> fromStream = depthStreamService.snapshotFor(user);
                    if (fromStream != null && !fromStream.isEmpty()) return fromStream;
                    return depthService.latestOrLive(user);
                })
                .orElseGet(java.util.List::of);
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
