package com.exittrading.app.controller;

import com.exittrading.app.dto.*;
import com.exittrading.app.service.screener.ScreenerService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/screener")
public class ScreenerController {

    private final ScreenerService screenerService;

    public ScreenerController(ScreenerService screenerService) {
        this.screenerService = screenerService;
    }

    @GetMapping("/columns")
    public ScreenerColumnsResponse columns() {
        return new ScreenerColumnsResponse(screenerService.getColumns());
    }

    @GetMapping("/latest-report-date")
    public ScreenerReportDateResponse latestReportDate() {
        return new ScreenerReportDateResponse(screenerService.latestReportDate());
    }

    @PostMapping("/{username}/query")
    public ScreenerQueryResponse query(@PathVariable String username,
                                       @RequestBody(required = false) ScreenerQueryRequest request) {
        ScreenerQueryRequest req = request != null ? request
                : new ScreenerQueryRequest(null, null, null, null, null, null, null);
        return screenerService.query(username, req);
    }

    @GetMapping("/{username}/presets")
    public List<ScreenerPresetDto> listPresets(@PathVariable String username) {
        return screenerService.listPresets(username);
    }

    @PostMapping("/{username}/presets")
    public ScreenerPresetDto createPreset(@PathVariable String username,
                                          @RequestBody ScreenerPresetRequest request) {
        return screenerService.createPreset(username, request);
    }

    @PutMapping("/{username}/presets/{id}")
    public ScreenerPresetDto updatePreset(@PathVariable String username,
                                          @PathVariable Long id,
                                          @RequestBody ScreenerPresetRequest request) {
        return screenerService.updatePreset(username, id, request);
    }

    @DeleteMapping("/{username}/presets/{id}")
    public java.util.Map<String, String> deletePreset(@PathVariable String username,
                                                      @PathVariable Long id) {
        screenerService.deletePreset(username, id);
        return java.util.Map.of("status", "deleted");
    }
}
