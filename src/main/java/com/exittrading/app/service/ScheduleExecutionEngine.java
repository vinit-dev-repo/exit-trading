package com.exittrading.app.service;

import com.exittrading.app.domain.ScheduleStatus;
import com.exittrading.app.domain.TradingSchedule;
import com.exittrading.app.dto.DepthView;
import com.exittrading.app.repository.TradingScheduleRepository;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Component
public class ScheduleExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(ScheduleExecutionEngine.class);

    private final TradingScheduleRepository scheduleRepository;
    private final ScheduleService scheduleService;
    private final KiteGateway kiteGateway;
    private final DepthService depthService;
    private final IstClock clock;
    private final AuditLogService auditLogService;

    private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(4, r -> {
        Thread t = new Thread(r);
        t.setName("schedule-exec-" + t.getId());
        t.setDaemon(true);
        return t;
    });

    private final Map<Long, ScheduledFuture<?>> runningTasks = new ConcurrentHashMap<>();

    public ScheduleExecutionEngine(TradingScheduleRepository scheduleRepository,
                                   ScheduleService scheduleService,
                                   KiteGateway kiteGateway,
                                   DepthService depthService,
                                   IstClock clock,
                                   AuditLogService auditLogService) {
        this.scheduleRepository = scheduleRepository;
        this.scheduleService = scheduleService;
        this.kiteGateway = kiteGateway;
        this.depthService = depthService;
        this.clock = clock;
        this.auditLogService = auditLogService;
    }

    @PostConstruct
    public void bootstrap() {
        List<TradingSchedule> schedules = scheduleRepository.findByStatus(ScheduleStatus.SCHEDULED);
        schedules.forEach(this::scheduleExecutionTask);
    }

    public void reschedule(TradingSchedule schedule) {
        cancelTask(schedule.getId());
        scheduleExecutionTask(schedule);
    }

    public void cancelTask(Long scheduleId) {
        ScheduledFuture<?> future = runningTasks.remove(scheduleId);
        if (future != null) {
            future.cancel(true);
        }
    }

    public void scheduleExecutionTask(TradingSchedule schedule) {
        if (schedule.getNextExecutionTime() == null || schedule.getStatus() != ScheduleStatus.SCHEDULED) {
            return;
        }
        ZonedDateTime executionTimeIst = schedule.getNextExecutionTime();
        long delayNanos = Duration.between(clock.now(), executionTimeIst).toNanos();
        if (delayNanos <= 0) {
            scheduler.submit(() -> executeSchedule(schedule.getId()));
            return;
        }
        ScheduledFuture<?> future = scheduler.schedule(() -> executeSchedule(schedule.getId()), delayNanos, TimeUnit.NANOSECONDS);
        runningTasks.put(schedule.getId(), future);
        log.info("Scheduled execution for {} at {} (in {} ms)", schedule.getTradingsymbol(), executionTimeIst, delayNanos / 1_000_000);
    }

    public void executeSchedule(Long scheduleId) {
        TradingSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new EntityNotFoundException("Schedule not found"));
        if (schedule.getStatus() != ScheduleStatus.SCHEDULED) {
            return;
        }
        try {
            if (schedule.isCancelOpenOrdersBeforeExecution()) {
                long cancelWindow = TimeUnit.SECONDS.toNanos(60);
                long now = System.nanoTime();
                long scheduleNano = now + Duration.between(clock.now(), schedule.getNextExecutionTime()).toNanos();
                if (scheduleNano - now <= cancelWindow) {
                    kiteGateway.cancelOpenOrders(schedule.getTradingsymbol(), schedule.getSide()).join();
                }
            }
            DepthView depth = depthService.captureDepth(schedule);
            String orderId = kiteGateway.placePcaOrder(schedule).get(1, TimeUnit.SECONDS);
            scheduleService.markExecuted(schedule, "Order Id: " + orderId);
            depthService.persistDepth(schedule.getUser(), depth);
            auditLogService.info(schedule, "Order executed", "Order Id: " + orderId);
            if (schedule.isAutoRepeat()) {
                TradingSchedule next = scheduleService.scheduleNextDay(schedule);
                scheduleExecutionTask(next);
                auditLogService.info(next, "Auto-repeat scheduled", "Next execution on " + next.getTradeDate());
            }
        } catch (TimeoutException tex) {
            scheduleService.markFailed(schedule, "Order placement timed out");
            log.error("Order placement timed out for {}", schedule.getTradingsymbol());
            auditLogService.error(schedule, "Order placement timed out", tex.getMessage());
        } catch (Exception ex) {
            scheduleService.markFailed(schedule, ex.getMessage());
            log.error("Execution failed for {}", schedule.getTradingsymbol(), ex);
            auditLogService.error(schedule, "Execution failure", ex.getMessage());
        } finally {
            runningTasks.remove(scheduleId);
        }
    }
}
