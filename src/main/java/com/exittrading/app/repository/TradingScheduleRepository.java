package com.exittrading.app.repository;

import com.exittrading.app.domain.ScheduleStatus;
import com.exittrading.app.domain.TradingSchedule;
import com.exittrading.app.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.ZonedDateTime;
import java.util.List;

public interface TradingScheduleRepository extends JpaRepository<TradingSchedule, Long> {

    List<TradingSchedule> findByUserAndStatusIn(UserAccount user, List<ScheduleStatus> statuses);

    @Query("select ts from TradingSchedule ts where ts.status = 'SCHEDULED' and ts.nextExecutionTime <= :threshold")
    List<TradingSchedule> findDueSchedules(ZonedDateTime threshold);

    List<TradingSchedule> findByStatus(ScheduleStatus status);

    List<TradingSchedule> findByUser(UserAccount user);
}
