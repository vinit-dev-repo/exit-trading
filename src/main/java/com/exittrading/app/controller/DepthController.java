package com.exittrading.app.controller;

import com.exittrading.app.dto.DepthView;
import com.exittrading.app.service.AdminService;
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

    public DepthController(DepthService depthService, AdminService adminService) {
        this.depthService = depthService;
        this.adminService = adminService;
    }

    @GetMapping("/{username}")
    public List<DepthView> latestDepth(@PathVariable String username) {
        return adminService.findOptionalByUsername(username)
                .map(depthService::latestOrLive)
                .orElseGet(java.util.List::of);
    }
}
