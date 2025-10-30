package com.exittrading.app.repository;

import com.exittrading.app.domain.ExecutionLog;
import com.exittrading.app.domain.TradingSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExecutionLogRepository extends JpaRepository<ExecutionLog, Long> {
    List<ExecutionLog> findTop50ByScheduleOrderByTimestampDesc(TradingSchedule schedule);
}
