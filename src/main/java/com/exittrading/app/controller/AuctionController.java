package com.exittrading.app.controller;

import com.exittrading.app.dto.DepthView;
import com.exittrading.app.dto.DetectionSummary;
import com.exittrading.app.service.AdminService;
import com.exittrading.app.service.DepthService;
import com.exittrading.app.service.DepthStreamService;
import com.exittrading.app.service.auction.AuctionAnalysisService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/auction")
public class AuctionController {

    private final AdminService adminService;
    private final DepthService depthService;
    private final DepthStreamService depthStreamService;
    private final AuctionAnalysisService analysisService;

    public AuctionController(AdminService adminService,
                             DepthService depthService,
                             DepthStreamService depthStreamService,
                             AuctionAnalysisService analysisService) {
        this.adminService = adminService;
        this.depthService = depthService;
        this.depthStreamService = depthStreamService;
        this.analysisService = analysisService;
    }

    @GetMapping("/summary/{username}")
    public List<DetectionSummary> summary(@PathVariable String username) {
        return adminService.findOptionalByUsername(username)
                .map(user -> {
                    List<DepthView> fromStream = depthStreamService.snapshotFor(user);
                    List<DepthView> latest = (fromStream != null && !fromStream.isEmpty()) ? fromStream : depthService.latestOrLive(user);
                    return analysisService.summarize(latest);
                })
                .orElseGet(java.util.List::of);
    }
}

