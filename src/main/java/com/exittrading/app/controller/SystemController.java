package com.exittrading.app.controller;

import com.exittrading.app.service.core.ShutdownService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/system")
public class SystemController {

    private final ShutdownService shutdownService;

    public SystemController(ShutdownService shutdownService) {
        this.shutdownService = shutdownService;
    }

    @PostMapping("/shutdown")
    public ResponseEntity<Map<String, Object>> shutdown() {
        boolean accepted = shutdownService.requestShutdown();
        return ResponseEntity.accepted().body(Map.of(
                "status", accepted ? "shutting_down" : "already_requested"
        ));
    }
}
