package com.exittrading.app.controller;

import com.exittrading.app.domain.ScheduleStatus;
import com.exittrading.app.dto.ScheduleRequest;
import com.exittrading.app.dto.ScheduleResponse;
import com.exittrading.app.service.AdminService;
import com.exittrading.app.service.ScheduleExecutionEngine;
import com.exittrading.app.service.ScheduleService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

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
    public ScheduleResponse create(@PathVariable String username, @RequestBody @Valid ScheduleRequest request) {
        ScheduleResponse response = scheduleService.createSchedule(username, request);
        executionEngine.scheduleExecutionTask(scheduleService.loadEntity(response.getId()));
        return response;
    }

    @PutMapping("/{scheduleId}")
    public ScheduleResponse update(@PathVariable Long scheduleId, @RequestBody @Valid ScheduleRequest request) {
        ScheduleResponse response = scheduleService.updateSchedule(scheduleId, request);
        executionEngine.reschedule(scheduleService.loadEntity(scheduleId));
        return response;
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
