package com.exittrading.app.controller;

import com.exittrading.app.domain.ScheduleStatus;
import com.exittrading.app.dto.ScheduleRequest;
import com.exittrading.app.dto.ScheduleResponse;
import com.exittrading.app.service.core.AdminService;
import com.exittrading.app.service.schedule.ScheduleExecutionEngine;
import com.exittrading.app.service.schedule.ScheduleService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

/**
 * Controller for managing trading schedules.
 * Supports CRUD operations for scheduled orders and manual execution triggers.
 */
@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {

    private final ScheduleService scheduleService;
    private final ScheduleExecutionEngine executionEngine;
    private final AdminService adminService;

    public ScheduleController(ScheduleService scheduleService,
                              ScheduleExecutionEngine executionEngine,
                              AdminService adminService) {
        this.scheduleService = scheduleService;
        this.executionEngine = executionEngine;
        this.adminService = adminService;
    }

    @GetMapping("/{username}")
    public List<ScheduleResponse> schedulesForUser(@PathVariable String username) {
        return scheduleService.fetchAllForUser(username);
    }

    @GetMapping("/{username}/active")
    public List<ScheduleResponse> activeSchedules(@PathVariable String username) {
        return scheduleService.fetchScheduled(username, Arrays.asList(ScheduleStatus.SCHEDULED));
    }

    @GetMapping("/status/{status}")
    public List<ScheduleResponse> byStatus(@PathVariable ScheduleStatus status) {
        return scheduleService.fetchByStatus(status);
    }

    @PostMapping("/{username}")
    public ResponseEntity<?> create(@PathVariable String username, @RequestBody @Valid ScheduleRequest request) {
        try {
            ScheduleResponse response = scheduleService.createSchedule(username, request);
            executionEngine.scheduleExecutionTask(scheduleService.loadEntity(response.getId()));
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException ex) {
            // Return user-friendly 400 without breaking the app
            return ResponseEntity.badRequest().body("Scheduled time is in the past. Please enable Manual Time and choose a future time.");
        }
    }

    @PutMapping("/{scheduleId}")
    public ResponseEntity<?> update(@PathVariable Long scheduleId, @RequestBody @Valid ScheduleRequest request) {
        try {
            ScheduleResponse response = scheduleService.updateSchedule(scheduleId, request);
            executionEngine.reschedule(scheduleService.loadEntity(scheduleId));
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body("Scheduled time is in the past. Please enable Manual Time and choose a future time.");
        }
    }

    @DeleteMapping("/{scheduleId}")
    public ResponseEntity<Void> cancel(@PathVariable Long scheduleId) {
        scheduleService.cancelSchedule(scheduleId);
        executionEngine.cancelTask(scheduleId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{scheduleId}/repeat")
    public ScheduleResponse repeatNextDay(@PathVariable Long scheduleId) {
        var entity = scheduleService.loadEntity(scheduleId);
        var next = scheduleService.scheduleNextDay(entity);
        executionEngine.scheduleExecutionTask(next);
        return scheduleService.toResponse(next);
    }
}
