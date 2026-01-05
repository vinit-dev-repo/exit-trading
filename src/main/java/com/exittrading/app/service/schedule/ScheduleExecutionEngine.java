package com.exittrading.app.service.schedule;

import com.exittrading.app.domain.ScheduleStatus;
import com.exittrading.app.domain.TradingSchedule;
import com.exittrading.app.dto.DepthView;
import com.exittrading.app.repository.TradingScheduleRepository;
import com.exittrading.app.service.core.AuditLogService;
import com.exittrading.app.service.core.DepthService;
import com.exittrading.app.service.core.InstrumentService;
import com.exittrading.app.service.core.IstClock;
import com.exittrading.app.service.core.KiteGateway;
import com.exittrading.app.service.execution.EsmExecutionService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Engine responsible for executing scheduled trades.
 * Manages the background tasks that trigger order execution at the scheduled times.
 */
@Component
public class ScheduleExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(ScheduleExecutionEngine.class);

    private final TradingScheduleRepository scheduleRepository;
    private final ScheduleService scheduleService;
    private final KiteGateway kiteGateway;
    private final DepthService depthService;
    private final IstClock clock;
    private final AuditLogService auditLogService;
    private final com.exittrading.app.repository.ExecutedTradeRepository executedTradeRepository;
    private final EsmExecutionService esmExecutionService;
    private final com.exittrading.app.service.core.SettingsService settingsService;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(25, r -> {
        Thread t = new Thread(r);
        t.setName("schedule-exec-" + t.getId());
        t.setUncaughtExceptionHandler((thr, ex) ->
                log.error("Uncaught exception in {}", thr.getName(), ex));
        return t;
    });

    private final Map<Long, ScheduledFuture<?>> runningTasks = new ConcurrentHashMap<>();

    public ScheduleExecutionEngine(TradingScheduleRepository scheduleRepository,
                                   ScheduleService scheduleService,
                                   KiteGateway kiteGateway,
                                   DepthService depthService,
                                   IstClock clock,
                                   AuditLogService auditLogService,
                                   com.exittrading.app.repository.ExecutedTradeRepository executedTradeRepository,
                                   EsmExecutionService esmExecutionService,
                                   com.exittrading.app.service.core.SettingsService settingsService) {
        this.scheduleRepository = scheduleRepository;
        this.scheduleService = scheduleService;
        this.kiteGateway = kiteGateway;
        this.depthService = depthService;
        this.clock = clock;
        this.auditLogService = auditLogService;
        this.executedTradeRepository = executedTradeRepository;
        this.esmExecutionService = esmExecutionService;
        this.settingsService = settingsService;
    }

    @PostConstruct
    public void bootstrap() {
        List<TradingSchedule> schedules = scheduleRepository.findByStatus(ScheduleStatus.SCHEDULED);
        schedules.forEach(this::scheduleExecutionTask);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
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

        // DELEGATE TO ESM SERVICE IF APPLICABLE
        if (esmExecutionService.shouldManage(schedule)) {
            // Calculate delay until execution time (or slightly before to start monitoring?)
            // We want to start monitoring BEFORE the slot to build OrderAgeTracker history.
            // E.g. 5 mins before.
            ZonedDateTime executionTimeIst = schedule.getNextExecutionTime();
            int monitorLeadMinutes = settingsService.getInt("app.config.esm.monitor-lead-minutes", 5);
            ZonedDateTime monitorStart = executionTimeIst.minusMinutes(monitorLeadMinutes);
            long delayNanos = Duration.between(clock.now(), monitorStart).toNanos();
            
            Runnable monitorTask = () -> esmExecutionService.monitor(schedule);
            
            if (delayNanos <= 0) {
                scheduler.submit(monitorTask);
            } else {
                ScheduledFuture<?> future = scheduler.schedule(monitorTask, delayNanos, TimeUnit.NANOSECONDS);
                runningTasks.put(schedule.getId(), future);
                log.info("Scheduled ESM Monitoring for {} to start at {}", schedule.getTradingsymbol(), monitorStart);
            }
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

        // Smart Staleness Check
        ZonedDateTime execTime = schedule.getNextExecutionTime();
        if (execTime != null) {
            long stalenessMillis = Duration.between(execTime, clock.now()).toMillis();
            int stalenessThresholdMin = settingsService.getInt("app.config.scheduler.staleness-threshold-min", 10);
            if (stalenessMillis > stalenessThresholdMin * 60 * 1000) { // Configurable Threshold
                log.warn("Skipping execution for {} as it is stale by {} ms", scheduleId, stalenessMillis);
                scheduleService.markFailed(schedule, "Expired: Stale execution > " + stalenessThresholdMin + "m");
                auditLogService.error(schedule, "Execution Skipped", "Stale execution > " + stalenessThresholdMin + "m");
                runningTasks.remove(scheduleId);
                return;
            }
        }
        
        com.exittrading.app.domain.ExecutedTrade trade = new com.exittrading.app.domain.ExecutedTrade();
        trade.setSchedule(schedule);
        trade.setUser(schedule.getUser());
        trade.setSymbol(schedule.getTradingsymbol());
        trade.setSide(schedule.getSide().name());
        trade.setExecutionTime(ZonedDateTime.now());
        
        try {
            if (schedule.isCancelOpenOrdersBeforeExecution()) {
                int cancelWindowSec = settingsService.getInt("app.config.scheduler.cancel-window-sec", 60);
                long cancelWindow = TimeUnit.SECONDS.toNanos(cancelWindowSec);
                long now = System.nanoTime();
                long scheduleNano = now + Duration.between(clock.now(), schedule.getNextExecutionTime()).toNanos();
                if (scheduleNano - now <= cancelWindow) {
                    try {
                        kiteGateway.cancelOpenOrders(schedule.getTradingsymbol(), schedule.getSide())
                                .get(45, TimeUnit.SECONDS);
                    } catch (TimeoutException te) {
                        log.warn("Timed out cancelling open orders for {}", schedule.getTradingsymbol());
                    }
                }
            }
            DepthView depth = depthService.captureDepth(schedule);
            // Allow a more reasonable window for order placement
            String orderId = kiteGateway.placePcaOrder(schedule).get(45, TimeUnit.SECONDS);
            
            trade.setExchangeOrderId(orderId);
            trade.setStatus("SUCCESS");
            trade.setFilledQty(schedule.getQuantity()); // Assuming full fill for log simplifiction
            trade.setFilledPrice(depth != null && depth.getLtp() != null ? depth.getLtp().doubleValue() : null);
            
            executedTradeRepository.save(trade);

            scheduleService.markExecuted(schedule, "Order Id: " + orderId);
            depthService.persistDepth(depth, schedule.getInstrumentToken(), false);
            auditLogService.info(schedule, "Order executed", "Order Id: " + orderId);
            if (schedule.isAutoRepeat()) {
                TradingSchedule next = scheduleService.scheduleNextDay(schedule);
                scheduleExecutionTask(next);
                auditLogService.info(next, "Auto-repeat scheduled", "Next execution on " + next.getTradeDate());
            }
        } catch (TimeoutException tex) {
            trade.setStatus("FAILED");
            trade.setRemarks("Timeout: " + tex.getMessage());
            executedTradeRepository.save(trade);
            
            scheduleService.markFailed(schedule, "Order placement timed out");
            log.error("Order placement timed out for {}", schedule.getTradingsymbol());
            auditLogService.error(schedule, "Order placement timed out", tex.getMessage());
        } catch (Exception ex) {
            trade.setStatus("FAILED");
            trade.setRemarks("Error: " + ex.getMessage());
            executedTradeRepository.save(trade);

            scheduleService.markFailed(schedule, ex.getMessage());
            log.error("Execution failed for {}", schedule.getTradingsymbol(), ex);
            auditLogService.error(schedule, "Execution failure", ex.getMessage());
        } finally {
            runningTasks.remove(scheduleId);
        }
    }
}
