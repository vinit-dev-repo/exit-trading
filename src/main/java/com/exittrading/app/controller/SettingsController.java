package com.exittrading.app.controller;

import com.exittrading.app.dto.SettingsResponse;
import com.exittrading.app.dto.SettingsUpdateRequest;
import com.exittrading.app.service.core.SettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public SettingsResponse list() {
        return new SettingsResponse(settingsService.list());
    }

    @PostMapping
    public SettingsResponse update(@RequestBody(required = false) SettingsUpdateRequest request) {
        Map<String, Object> updates = request != null ? request.updates() : Map.of();
        settingsService.update(updates);
        return new SettingsResponse(settingsService.list());
    }

    @PostMapping("/restore")
    public SettingsResponse restore() {
        settingsService.restoreDefaults();
        return new SettingsResponse(settingsService.list());
    }
}
