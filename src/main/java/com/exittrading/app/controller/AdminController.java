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
        // Use a mutable map to allow null values for JSON serialization.
        // Map.of(...) rejects nulls and was causing NPEs when expiry/user were null.
        java.util.Map<String, Object> resp = new java.util.HashMap<>();
        resp.put("expiry", sessionManager.getExpiry());
        resp.put("active", sessionManager.getExpiry() != null);
        resp.put("user", sessionManager.getUserName());
        return resp;
    }

    // Debug helper: fetch holdings for a specific user (to verify backend has them)
    @GetMapping("/users/{username}/holdings")
    public Set<String> userHoldings(@PathVariable String username) {
        return adminService.findOptionalByUsername(username)
                .map(UserAccount::getHoldings)
                .orElseGet(java.util.Set::of);
    }
}
