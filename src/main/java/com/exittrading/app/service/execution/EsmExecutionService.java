package com.exittrading.app.service.execution;

import com.exittrading.app.config.SessionSlotConfig;
import com.exittrading.app.domain.EsmMonitorState;
import com.exittrading.app.domain.ScheduleStatus;
import com.exittrading.app.domain.SessionSlot;
import com.exittrading.app.domain.TradingSchedule;
import com.exittrading.app.dto.DepthView;
import com.exittrading.app.repository.EsmMonitorStateRepository;
import com.exittrading.app.repository.TradingScheduleRepository;
import com.exittrading.app.service.auction.AuctionAnalysisService;
import com.exittrading.app.service.auction.OrderAgeTracker;
import com.exittrading.app.service.core.AuditLogService;
import com.exittrading.app.service.core.DepthService;
import com.exittrading.app.service.core.DepthStreamService;
import com.exittrading.app.service.core.KiteGateway;
import com.exittrading.app.service.schedule.ScheduleService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Service to manage execution of ESM Stage 2 orders.
 * Implements "Gated Entry" logic: exits are only attempted when D_down signal is true.
 */
@Service
public class EsmExecutionService {

    private static final Logger log = LoggerFactory.getLogger(EsmExecutionService.class);

    private final KiteGateway kiteGateway;
    private final DepthService depthService;
    private final DepthStreamService depthStreamService;
    private final AuctionAnalysisService auctionAnalysisService;
    private final OrderAgeTracker orderAgeTracker;
    private final ScheduleService scheduleService;
    private final TradingScheduleRepository scheduleRepository;
    private final AuditLogService auditLogService;
    private final com.exittrading.app.service.core.IstClock istClock;
    private final com.exittrading.app.repository.ExecutedTradeRepository executedTradeRepository;
    private final EsmMonitorStateRepository esmMonitorStateRepository;
    private final SessionSlotConfig sessionSlotConfig;
    private final com.exittrading.app.service.core.SettingsService settingsService;

    private static final String STATE_PRE_ENTRY = "PRE_ENTRY";
    private static final String STATE_POST_ENTRY = "POST_ENTRY";
    private static final String STATE_SNIPER = "SNIPER";

    private final ScheduledExecutorService sniperScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r);
        t.setName("esm-sniper-" + t.getId());
        t.setDaemon(true);
        t.setUncaughtExceptionHandler((thr, ex) -> log.error("Uncaught exception in {}", thr.getName(), ex));
        return t;
    });
    private final Map<Long, ScheduledFuture<?>> sniperTasks = new ConcurrentHashMap<>();

    // Active schedules being monitored: ScheduleID -> Schedule
    private final Map<Long, TradingSchedule> monitoredSchedules = new ConcurrentHashMap<>();
    
    // Placed orders being monitored for cancellation: OrderID -> MonitoredOrder
    private final Map<String, MonitoredOrder> monitoredPlacements = new ConcurrentHashMap<>();

    private static final long AUTO_EXIT_LOG_THROTTLE_MS = 60000L;
    private final Map<Long, Long> autoExitSuppressLogAt = new ConcurrentHashMap<>();

    public EsmExecutionService(KiteGateway kiteGateway,
                               DepthService depthService,
                               DepthStreamService depthStreamService,
                               AuctionAnalysisService auctionAnalysisService,
                               OrderAgeTracker orderAgeTracker,
                               ScheduleService scheduleService,
                               TradingScheduleRepository scheduleRepository,
                               AuditLogService auditLogService,
                               com.exittrading.app.service.core.IstClock istClock,
                               com.exittrading.app.repository.ExecutedTradeRepository executedTradeRepository,
                               EsmMonitorStateRepository esmMonitorStateRepository,
                               SessionSlotConfig sessionSlotConfig,
                               com.exittrading.app.service.core.SettingsService settingsService) {
        this.kiteGateway = kiteGateway;
        this.depthService = depthService;
        this.depthStreamService = depthStreamService;
        this.auctionAnalysisService = auctionAnalysisService;
        this.orderAgeTracker = orderAgeTracker;
        this.scheduleService = scheduleService;
        this.scheduleRepository = scheduleRepository;
        this.auditLogService = auditLogService;
        this.istClock = istClock;
        this.executedTradeRepository = executedTradeRepository;
        this.esmMonitorStateRepository = esmMonitorStateRepository;
        this.sessionSlotConfig = sessionSlotConfig;
        this.settingsService = settingsService;
    }

    @PostConstruct
    public void restoreState() {
        try {
            List<EsmMonitorState> states = esmMonitorStateRepository.findByActiveTrue();
            for (EsmMonitorState state : states) {
                restoreState(state);
            }
            log.info("Restored {} ESM monitor states", states.size());
        } catch (Exception e) {
            log.warn("Failed to restore ESM monitor state: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        try {
            sniperScheduler.shutdownNow();
        } catch (Exception e) {
            log.warn("Failed to shutdown sniper scheduler: {}", e.getMessage());
        }
    }

    // --- Phase 5 & 6: Hazard Zone & Sniper ---
    
    // Tracks schedules flagged for Aggressive Rollover
    private final Set<Long> aggressiveRolloverSchedules = ConcurrentHashMap.newKeySet();

    /**
     * Registers a schedule for ESM monitoring and potential execution.
     * Called by ScheduleExecutionEngine instead of direct execution.
     */
    public void monitor(TradingSchedule schedule) {
        if (schedule == null || schedule.getId() == null) return;
        monitoredSchedules.put(schedule.getId(), schedule);
        upsertState(schedule.getId(), STATE_PRE_ENTRY, null, null, "[MONITOR_START]", null);
        // Reset analysis stats for new session monitoring
        auctionAnalysisService.resetPhantomWallStats(schedule.getTradingsymbol());
        log.info("Started ESM Monitoring for Schedule ID: {} ({})", schedule.getId(), schedule.getTradingsymbol());
        auditLogService.info(schedule, "ESM Monitoring Started", "[MONITOR_START] Waiting for D_down signal...");
    }

    /**
     * Periodic active loop to check indicators and execute if triggered.
     * Also checks placed orders for cancellation if signal reverts.
     * Runs every 1 second.
     */
    @Scheduled(fixedRate = 1000)
    public void runExecutionLoop() {
        // 1. Monitor Pending Schedules
        if (!monitoredSchedules.isEmpty()) {
            for (Long id : monitoredSchedules.keySet()) {
                try {
                    processSchedule(id);
                } catch (Exception e) {
                    log.error("Error processing ESM schedule {}", id, e);
                }
            }
        }
        
        // 2. Monitor Placed Orders (Cancellation Logic & Hazard Timeout)
        if (!monitoredPlacements.isEmpty()) {
             for (String orderId : monitoredPlacements.keySet()) {
                 try {
                     monitorPlacedOrder(orderId);
                 } catch (Exception e) {
                     log.error("Error monitoring placed order {}", orderId, e);
                 }
             }
        }
    }

    private void processSchedule(Long id) {
        TradingSchedule schedule = monitoredSchedules.get(id);
        if (schedule == null) return;

        ZonedDateTime now = istClock.now();
        ZonedDateTime sessionStart = schedule.getNextExecutionTime(); 
        
        // Hazard Zone Timings (hardcoded relative to session start)
        // Start: 44m 00s
        ZonedDateTime hazardStart = sessionStart.plusMinutes(getHazardStartMinutes());
        ZonedDateTime hazardTimeout = hazardStart.plusSeconds(getHazardTimeoutSeconds());
        ZonedDateTime deadline = sessionStart.plusMinutes(getSessionLengthMinutes()).plusSeconds(getHazardDeadlineSeconds());

        if (now.isAfter(deadline)) {
            handleExpired(schedule);
            return;
        }

        // 2. Refresh Market Data
        DepthView depth = getLatestDepth(schedule);
        if (depth == null) {
            return; 
        }
        
        // --- Phase 5: Hazard Zone Logic ---
        
        // A. Phantom Wall Check (Always Active)
        if (auctionAnalysisService.detectPhantomWall(depth)) {
            log.warn("Phantom Wall Detected for {}! Immediate Exit.", schedule.getTradingsymbol());
            if (shouldPlaceOrder(schedule, "PHANTOM_WALL", false)) {
                executeOrder(schedule, depth, "PHANTOM_WALL");
            }
            return;
        }
        
        // B. Dynamic Supply Pressure (Active after Hazard Start)
        if (now.isAfter(hazardStart) && now.isBefore(hazardTimeout)) {
            if (auctionAnalysisService.getHazardRisk(depth, schedule.getQuantity())) {
                log.info("Hazard Zone Trigger: Supply Pressure Critical for {}. Executing.", schedule.getTradingsymbol());
                if (shouldPlaceOrder(schedule, "HAZARD_PRESSURE", false)) {
                    executeOrder(schedule, depth, "HAZARD_PRESSURE");
                }
                return;
            }
        }
        
        // --- Phase 2: D_down Logic ---

        // 3. Update Tracker & Calc Indicators
        orderAgeTracker.update(schedule.getTradingsymbol(), depth);
        
        Double prevClose = depth.getPrevClose() != null ? depth.getPrevClose().doubleValue() : 0.0;
        AuctionAnalysisService.EsmIndicators indicators = auctionAnalysisService.calculateIndicators(
                depth, orderAgeTracker, prevClose, null, null // Open0930/ManualUC optional
        );

        // 4. Decision Gate
        if (indicators.dDown) {
            log.info("ESM Trigger [D_down] for {}. Reason: {}. Executing LC Order!", schedule.getTradingsymbol(), indicators.reason);
            if (shouldPlaceOrder(schedule, "ESM_D_DOWN", false)) {
                executeOrder(schedule, depth, "ESM_D_DOWN");
            }
        }
    }

    private void executeOrder(TradingSchedule schedule, DepthView depth) {
        executeOrder(schedule, depth, "ESM Trigger");
    }

    private void executeOrder(TradingSchedule schedule, DepthView depth, String reason) {
        // Remove from Pre-Entry Monitor
        monitoredSchedules.remove(schedule.getId());
        cancelSniperTask(schedule.getId());

        com.exittrading.app.domain.ExecutedTrade trade = new com.exittrading.app.domain.ExecutedTrade();
        trade.setSchedule(schedule);
        trade.setUser(schedule.getUser());
        trade.setSymbol(schedule.getTradingsymbol());
        trade.setSide(schedule.getSide().name());
        trade.setExecutionTime(istClock.now());
        trade.setRemarks(reason);

        try {
            if (schedule.isCancelOpenOrdersBeforeExecution()) {
                 try {
                     kiteGateway.cancelOpenOrders(schedule.getTradingsymbol(), schedule.getSide())
                             .get(10, TimeUnit.SECONDS);
                 } catch (Exception ignored) {}
            }
            
            String orderId = kiteGateway.placePcaOrder(schedule).get(45, TimeUnit.SECONDS);

            trade.setExchangeOrderId(orderId);
            trade.setStatus("SUCCESS"); // This means "Successfully Placed", not filled.
            trade.setFilledQty(schedule.getQuantity()); // Assuming filled for record, but tracking real fill in monitor?
            trade.setFilledPrice(depth != null && depth.getLtp() != null ? depth.getLtp().doubleValue() : null);

            executedTradeRepository.save(trade);
            scheduleService.markExecuted(schedule, "ESM Triggered: " + orderId);
            auditLogService.info(schedule, "ESM Executed", "[ESM_TRIGGER] Triggered by " + reason + ". Order: " + orderId);
            
            // Add to Post-Entry Monitor
            monitoredPlacements.put(orderId, new MonitoredOrder(orderId, schedule, trade.getTradeId(), reason));
            upsertState(schedule.getId(), STATE_POST_ENTRY, orderId, trade.getTradeId(), reason, null);

            // Don't auto-schedule next day yet; wait for filled or rollover signal
        } catch (Exception e) {
            trade.setStatus("FAILED");
            trade.setRemarks(e.getMessage());
            executedTradeRepository.save(trade);
            scheduleService.markFailed(schedule, "ESM Exec Failed: " + e.getMessage());
            auditLogService.error(schedule, "ESM Execution Failed", e.getMessage());
            deactivateStateByScheduleId(schedule.getId());
            
            // Phase 6 Latch: If failed to place during Hazard Pressure, queue for Sniper?
            // Actually, usually means API error. But if it was HAZARD, maybe we try again.
            if ("HAZARD_PRESSURE".equals(reason)) {
                armSniper(schedule);
            }
        }
    }
    
    private void monitorPlacedOrder(String orderId) {
        MonitoredOrder ctx = monitoredPlacements.get(orderId);
        if (ctx == null) return;
        
        // Hazard Timeout Check (Conditional Cancel)
        if ("HAZARD_PRESSURE".equals(ctx.reason)) {
            ZonedDateTime now = istClock.now();
            ZonedDateTime hazardTimeout = ctx.schedule.getNextExecutionTime()
                    .plusMinutes(getHazardStartMinutes())
                    .plusSeconds(getHazardTimeoutSeconds());
            
            if (now.isAfter(hazardTimeout)) {
                // Check if safe to cancel?
                 DepthView depth = getLatestDepth(ctx.schedule);
                 if (depth != null) {
                     boolean risky = auctionAnalysisService.getHazardRisk(depth, ctx.schedule.getQuantity());
                     if (!risky) {
                         // Safe now. False alarm. Cancel.
                         log.info("Hazard Timeout: False alarm for {}. Cancelling defensive order.", ctx.schedule.getTradingsymbol());
                         kiteGateway.cancelOrder(orderId); // Async fire & forget
                         monitoredPlacements.remove(orderId); // Stop monitoring
                         deactivateStateByOrderId(orderId);
                         
                         // We removed it, so effectively "un-executed". 
                         // To resume monitoring, we'd need to re-add to monitoredSchedules.
                         // But for simplicity, we treat it as "Saved/Held".
                         auditLogService.info(ctx.schedule, "Defensive Cancel", "[HAZARD_SAFE] Risk subsided. Holding position.");
                         return;
                     }
                 }
            }
        }
        
        // Check Status
        try {
            com.zerodhatech.models.Order order = kiteGateway.getOrder(orderId).get(5, TimeUnit.SECONDS);
            if (order == null) return;
            
            String status = order.status != null ? order.status.toUpperCase() : "UNKNOWN";
            
            // Terminal States
            if ("COMPLETE".equals(status)) {
                monitoredPlacements.remove(orderId);
                deactivateStateByOrderId(orderId);
                // Filled! Schedule next day.
                if (ctx.schedule.isAutoRepeat()) {
                    scheduleService.scheduleNextDay(ctx.schedule);
                }
                return;
            }
            
            if ("CANCELLED".equals(status) || "REJECTED".equals(status)) {
                monitoredPlacements.remove(orderId);
                deactivateStateByOrderId(orderId);
                
                // Phase 6 Check: If this was a valid attempt that failed/cancelled at session end (unfilled), we arm sniper.
                // We don't know "session end" easily here w/o time check.
                // But generally if cancelled, we might want to try again next session.
                return;
            }
            
            // Active States: OPEN, TRIGGER PENDING
            if ("OPEN".equals(status) || "TRIGGER PENDING".equals(status)) {
                
                // Phase 2 Logic: D_down reversal check
                // Only if NOT a Hazard Pressure or Phantom Wall order (those stick)
                if ("ESM_D_DOWN".equals(ctx.reason)) {
                    DepthView depth = getLatestDepth(ctx.schedule);
                    if (depth != null) {
                        orderAgeTracker.update(ctx.schedule.getTradingsymbol(), depth);
                        Double prevClose = depth.getPrevClose() != null ? depth.getPrevClose().doubleValue() : 0.0;
                        AuctionAnalysisService.EsmIndicators indicators = auctionAnalysisService.calculateIndicators(
                                depth, orderAgeTracker, prevClose, null, null
                        );
                        
                        if (!indicators.dDown) {
                            // Signal Reversed! CANCEL.
                            log.info("ESM Signal Reversal for {}. D_down is FALSE. Cancelling Order {}", ctx.schedule.getTradingsymbol(), orderId);
                            kiteGateway.cancelOrder(orderId).get(5, TimeUnit.SECONDS);
                            
                             executedTradeRepository.findById(ctx.tradeId).ifPresent(t -> {
                                 t.setStatus("CANCELLED");
                                 t.setRemarks("Cancelled: Signal Reversal");
                                 executedTradeRepository.save(t);
                             });
                             auditLogService.warn(ctx.schedule, "ESM Cancelled", "[SIGNAL_REVERSAL] Signal reversed (Risk subsided). Order cancelled.");
                             monitoredPlacements.remove(orderId);
                             deactivateStateByOrderId(orderId);
                        }
                    }
                }
            }
            
        } catch (Exception e) {
            // ignore transient
        }
    }

    private void handleExpired(TradingSchedule schedule) {
        monitoredSchedules.remove(schedule.getId());
        // Phase 6 Check: If we expired without execution, are we "Unsafe"?
        // If Hazard Risk is TRUE at expiration, we arm Sniper.
        DepthView depth = getLatestDepth(schedule);
        boolean risky = false;
        if (depth != null) {
             risky = auctionAnalysisService.getHazardRisk(depth, schedule.getQuantity());
        }
        
        if (risky) {
            log.warn("Session Expired but Risk is High for {}. Arming Aggressive Rollover Sniper!", schedule.getTradingsymbol());
            armSniper(schedule);
        } else {
            log.info("ESM Schedule {} expired without D_down signal.", schedule.getId());
            scheduleService.markFailed(schedule, "Skipped: No Downside Signal within window");
            auditLogService.warn(schedule, "ESM Skipped", "[NO_TRIGGER] Price held up. No exit trigger.");
            deactivateStateByScheduleId(schedule.getId());
            
            // Auto schedule next day anyway? No, only on fill usually. 
            // But if we held successfully, we repeat? Usually yes.
            if (schedule.isAutoRepeat()) {
                 scheduleService.scheduleNextDay(schedule);
            }
    }
    }
    
    // --- Phase 6: Sniper Logic ---
    
    private void armSniper(TradingSchedule schedule) {
        if (!schedule.isAutoRepeat()) return;
        ZonedDateTime nextStart = resolveNextSessionStart();
        if (nextStart == null) return;

        schedule.setNextExecutionTime(nextStart);
        schedule.setTradeDate(nextStart.toLocalDate());
        SessionSlot slot = resolveSessionSlot(nextStart.toLocalTime());
        if (slot != null) schedule.setSessionSlot(slot);
        scheduleRepository.save(schedule);

        long delayMs = calculateDelayToNextSessionSniper(nextStart);
        if (delayMs >= 0) {
            aggressiveRolloverSchedules.add(schedule.getId());
            scheduleSniperTask(schedule, nextStart, "SNIPER_ROLLOVER", delayMs);
            upsertState(schedule.getId(), STATE_SNIPER, null, null, "SNIPER_ROLLOVER", nextStart);
            log.info("Sniper Armed for {} at {} (delay {} ms)", schedule.getTradingsymbol(), nextStart, delayMs);
        }
    }
    
    private long calculateDelayToNextSessionSniper(ZonedDateTime nextStart) {
        if (nextStart == null) return -1L;
        long delay = Duration.between(istClock.now(), nextStart).toMillis() - Math.max(0, getSniperPrefireMs());
        return Math.max(0L, delay);
    }

    private void scheduleSniperTask(TradingSchedule schedule, ZonedDateTime nextStart, String reason, long delayMs) {
        if (schedule == null || schedule.getId() == null) return;
        cancelSniperTask(schedule.getId());
        ScheduledFuture<?> future = sniperScheduler.schedule(() -> {
            try {
                if (schedule.getStatus() != ScheduleStatus.SCHEDULED) {
                    deactivateStateByScheduleId(schedule.getId());
                    return;
                }
                DepthView depth = getLatestDepth(schedule);
                if (depth == null) {
                    log.warn("Sniper depth unavailable for {}", schedule.getTradingsymbol());
                    return;
                }
                if (shouldPlaceOrder(schedule, reason, true)) {
                    executeOrder(schedule, depth, reason);
                }
            } catch (Exception e) {
                log.error("Sniper execution failed for {}: {}", schedule.getTradingsymbol(), e.getMessage());
            }
        }, delayMs, TimeUnit.MILLISECONDS);
        sniperTasks.put(schedule.getId(), future);
    }

    private ZonedDateTime resolveNextSessionStart() {
        List<LocalTime> slots = (sessionSlotConfig != null) ? sessionSlotConfig.orderedTimes() : List.of();
        if (slots == null || slots.isEmpty()) {
            slots = SessionSlot.ordered().stream().map(SessionSlot::getTime).toList();
        }
        if (slots.isEmpty()) return null;

        ZonedDateTime now = istClock.now();
        LocalDate date = now.toLocalDate();
        LocalTime nowTime = now.toLocalTime();
        for (LocalTime slot : slots) {
            if (slot.isAfter(nowTime)) {
                return ZonedDateTime.of(date, slot, istClock.zoneId());
            }
        }
        return ZonedDateTime.of(date.plusDays(1), slots.get(0), istClock.zoneId());
    }

    private SessionSlot resolveSessionSlot(LocalTime time) {
        if (time == null) return null;
        for (SessionSlot slot : SessionSlot.ordered()) {
            if (slot.getTime().equals(time)) return slot;
        }
        return null;
    }

    private void cancelSniperTask(Long scheduleId) {
        ScheduledFuture<?> future = sniperTasks.remove(scheduleId);
        if (future != null) {
            future.cancel(true);
        }
    }

    private boolean shouldPlaceOrder(TradingSchedule schedule, String reason, boolean deactivateState) {
        if (settingsService.getBoolean("app.config.esm.auto-exit-enabled", true)) return true;
        if (schedule == null || schedule.getId() == null) return false;
        long now = System.currentTimeMillis();
        Long last = autoExitSuppressLogAt.get(schedule.getId());
        if (last == null || now - last > AUTO_EXIT_LOG_THROTTLE_MS) {
            autoExitSuppressLogAt.put(schedule.getId(), now);
            String msg = "Auto-exit disabled; skipped " + reason + " for " + schedule.getTradingsymbol();
            log.warn(msg);
            auditLogService.warn(schedule, "ESM Auto-Exit Disabled", "[ESM_SUPPRESSED] " + msg);
        }
        if (deactivateState) {
            deactivateStateByScheduleId(schedule.getId());
        }
        return false;
    }

    private DepthView getLatestDepth(TradingSchedule schedule) {
        if (schedule == null) return null;
        DepthView depth = null;
        if (schedule.getInstrumentToken() != null) {
            depth = depthStreamService.getSnapshot(schedule.getInstrumentToken());
        }
        if (isStale(depth)) {
            try {
                DepthView fallback = depthService.fetchOne(schedule.getTradingsymbol(), schedule.getInstrumentToken());
                if (fallback != null) depth = fallback;
            } catch (Exception e) {
                log.debug("Depth fallback failed for {}: {}", schedule.getTradingsymbol(), e.getMessage());
            }
        }
        return depth;
    }

    private boolean isStale(DepthView depth) {
        if (depth == null || depth.getCapturedAt() == null) return true;
        int staleSec = settingsService.getInt("app.config.esm.snapshot-stale-sec", 30);
        ZonedDateTime cutoff = istClock.now().minusSeconds(Math.max(5, staleSec));
        return depth.getCapturedAt().isBefore(cutoff);
    }

    private void restoreState(EsmMonitorState state) {
        if (state == null || state.getScheduleId() == null) return;
        Optional<TradingSchedule> scheduleOpt = scheduleRepository.findById(state.getScheduleId());
        if (scheduleOpt.isEmpty()) {
            deactivateState(state);
            return;
        }
        TradingSchedule schedule = scheduleOpt.get();
        String mode = state.getState();
        if (STATE_PRE_ENTRY.equals(mode)) {
            if (schedule.getStatus() == ScheduleStatus.SCHEDULED) {
                monitoredSchedules.put(schedule.getId(), schedule);
            } else {
                deactivateState(state);
            }
            return;
        }
        if (STATE_POST_ENTRY.equals(mode)) {
            if (state.getOrderId() != null && state.getTradeId() != null) {
                monitoredPlacements.put(state.getOrderId(), new MonitoredOrder(state.getOrderId(), schedule, state.getTradeId(), state.getReason()));
            } else {
                deactivateState(state);
            }
            return;
        }
        if (STATE_SNIPER.equals(mode)) {
            if (state.getNextActionAt() == null) {
                deactivateState(state);
                return;
            }
            long delayMs = calculateDelayToNextSessionSniper(state.getNextActionAt());
            if (delayMs < 0) {
                deactivateState(state);
                return;
            }
            scheduleSniperTask(schedule, state.getNextActionAt(), state.getReason() != null ? state.getReason() : "SNIPER_ROLLOVER", delayMs);
            return;
        }
        deactivateState(state);
    }

    private void upsertState(Long scheduleId, String state, String orderId, java.util.UUID tradeId, String reason, ZonedDateTime nextActionAt) {
        if (scheduleId == null || state == null) return;
        EsmMonitorState record = esmMonitorStateRepository
                .findTopByScheduleIdAndActiveTrueOrderByUpdatedAtDesc(scheduleId)
                .orElseGet(EsmMonitorState::new);
        record.setScheduleId(scheduleId);
        record.setState(state);
        record.setOrderId(orderId);
        record.setTradeId(tradeId);
        record.setReason(reason);
        record.setNextActionAt(nextActionAt);
        record.setActive(true);
        esmMonitorStateRepository.save(record);
    }

    private void deactivateStateByScheduleId(Long scheduleId) {
        if (scheduleId == null) return;
        esmMonitorStateRepository.findTopByScheduleIdAndActiveTrueOrderByUpdatedAtDesc(scheduleId)
                .ifPresent(this::deactivateState);
    }

    private void deactivateStateByOrderId(String orderId) {
        if (orderId == null) return;
        esmMonitorStateRepository.findTopByOrderIdAndActiveTrueOrderByUpdatedAtDesc(orderId)
                .ifPresent(this::deactivateState);
    }

    private void deactivateState(EsmMonitorState state) {
        if (state == null) return;
        state.setActive(false);
        state.setUpdatedAt(istClock.now());
        esmMonitorStateRepository.save(state);
    }
    
    // For modifying Engine to check capability
    public boolean shouldManage(TradingSchedule schedule) {
        // Manage if it has a SESSION SLOT (Implies Periodic Call Auction)
        // AND it is a SELL order (Exit).
        return schedule.getSessionSlot() != null 
               && schedule.getSide() == com.exittrading.app.domain.OrderSide.SELL;
    }
    
    private static class MonitoredOrder {
        final String orderId;
        final TradingSchedule schedule;
        final java.util.UUID tradeId;
        final String reason;
        MonitoredOrder(String orderId, TradingSchedule schedule, java.util.UUID tradeId, String reason) {
            this.orderId = orderId;
            this.schedule = schedule;
            this.tradeId = tradeId;
            this.reason = reason;
        }
    }

    private int getHazardStartMinutes() {
        return settingsService.getInt("app.config.esm.hazard-start-minutes", 44);
    }

    private int getSessionLengthMinutes() {
        return settingsService.getInt("app.config.esm.session-length-minutes", 45);
    }

    private int getHazardTimeoutSeconds() {
        return settingsService.getInt("app.config.esm.hazard-timeout-seconds", 50);
    }

    private int getHazardDeadlineSeconds() {
        return settingsService.getInt("app.config.esm.deadline-seconds", 5);
    }

    private long getSniperPrefireMs() {
        return settingsService.getLong("app.config.esm.sniper-prefire-ms", 200);
    }
}
