package com.exittrading.app.controller;

import com.exittrading.app.domain.UserAccount;
import com.exittrading.app.service.AdminService;
import com.exittrading.app.service.KiteSessionManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;
    private final KiteSessionManager sessionManager;

    public AdminController(AdminService adminService, KiteSessionManager sessionManager) {
        this.adminService = adminService;
        this.sessionManager = sessionManager;
    }

    @GetMapping("/users")
    public List<UserAccount> users() {
        return adminService.allUsers();
    }

    @PostMapping("/logging/{username}")
    public ResponseEntity<Void> toggleLogging(@PathVariable String username, @RequestBody Map<String, Boolean> payload) {
        adminService.toggleLogging(username, Boolean.TRUE.equals(payload.get("enabled")));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/holdings/{username}")
    public ResponseEntity<Void> updateHoldings(@PathVariable String username, @RequestBody Set<String> holdings) {
        adminService.updateHoldings(username, holdings);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/session/status")
    public Map<String, Object> sessionStatus() {
        return Map.of(
                "expiry", sessionManager.getExpiry(),
                "active", sessionManager.getExpiry() != null,
                "user", sessionManager.getUserName()
        );
    }
}
