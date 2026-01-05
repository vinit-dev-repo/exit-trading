package com.exittrading.app.controller;

import com.exittrading.app.dto.InstrumentSearchResult;
import com.exittrading.app.dto.LoggingScripRequest;
import com.exittrading.app.dto.LoggingScripView;
import com.exittrading.app.service.core.InstrumentService;
import com.exittrading.app.service.scrip.LoggingScripService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Controller for managing watched scrips for market depth logging.
 * Allows adding, removing, toggling, and manually transforming scrips.
 */
@RestController
@RequestMapping("/api/logging")
public class LoggingScripController {

    private final LoggingScripService loggingScripService;
    private final InstrumentService instrumentService;

    public LoggingScripController(LoggingScripService loggingScripService, InstrumentService instrumentService) {
        this.loggingScripService = loggingScripService;
        this.instrumentService = instrumentService;
    }

    @GetMapping("/search")
    public List<InstrumentSearchResult> search(@RequestParam("q") String query,
                                               @RequestParam(name = "limit", defaultValue = "15") int limit) {
        int capped = Math.max(1, Math.min(limit, 30));
        return instrumentService.search(query, capped);
    }
    
    @GetMapping("/unresolved")
    public java.util.Set<Long> getUnresolved() {
        return instrumentService.getUnresolvedTokens();
    }

    @GetMapping("/scrips/{username}")
    public List<LoggingScripView> list(@PathVariable String username) {
        return loggingScripService.list(username);
    }

    @PostMapping("/scrips/{username}")
    public LoggingScripView add(@PathVariable String username, @RequestBody LoggingScripRequest request) {
        return loggingScripService.add(username, request);
    }

    @DeleteMapping("/scrips/{username}/{id}")
    public ResponseEntity<Void> delete(@PathVariable String username, @PathVariable Long id) {
        loggingScripService.remove(username, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/scrips/{username}/{id}/toggle")
    public LoggingScripView toggle(@PathVariable String username,
                                   @PathVariable Long id,
                                   @RequestBody Map<String, Boolean> payload) {
        boolean active = payload != null && Boolean.TRUE.equals(payload.get("active"));
        return loggingScripService.toggle(username, id, active);
    }

    @PostMapping("/scrips/{username}/{id}/capture")
    public LoggingScripView captureOne(@PathVariable String username, @PathVariable Long id) {
        return loggingScripService.captureOne(username, id);
    }

    @PostMapping("/scrips/{username}/capture")
    public List<LoggingScripView> captureAll(@PathVariable String username) {
        return loggingScripService.captureForUser(username);
    }
}
