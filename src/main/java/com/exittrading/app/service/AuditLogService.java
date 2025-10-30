package com.exittrading.app.service;

import com.exittrading.app.domain.ExecutionLog;
import com.exittrading.app.domain.TradingSchedule;
import com.exittrading.app.repository.ExecutionLogRepository;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;

@Service
public class AuditLogService {

    private final ExecutionLogRepository repository;
    private final IstClock clock;

    public AuditLogService(ExecutionLogRepository repository, IstClock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public void info(TradingSchedule schedule, String message, String detail) {
        save(schedule, "INFO", message, detail);
    }

    public void error(TradingSchedule schedule, String message, String detail) {
        save(schedule, "ERROR", message, detail);
    }

    public void warn(TradingSchedule schedule, String message, String detail) {
        save(schedule, "WARN", message, detail);
    }

    private void save(TradingSchedule schedule, String level, String message, String detail) {
        ExecutionLog log = new ExecutionLog(schedule, level, message, detail, clock.now());
        repository.save(log);
    }
}
