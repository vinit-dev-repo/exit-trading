package com.exittrading.app.service.execution;

import com.exittrading.app.domain.OrderSide;
import com.exittrading.app.domain.TradingSchedule;
import com.exittrading.app.dto.DepthView;
import com.exittrading.app.repository.ExecutedTradeRepository;
import com.exittrading.app.repository.TradingScheduleRepository;
import com.exittrading.app.service.auction.AuctionAnalysisService;
import com.exittrading.app.service.auction.OrderAgeTracker;
import com.exittrading.app.service.core.AuditLogService;
import com.exittrading.app.service.core.DepthService;
import com.exittrading.app.service.core.DepthStreamService;
import com.exittrading.app.service.core.IstClock;
import com.exittrading.app.service.core.KiteGateway;
import com.exittrading.app.service.core.SettingsService;
import com.exittrading.app.service.schedule.ScheduleService;
import com.exittrading.app.repository.EsmMonitorStateRepository;
import com.exittrading.app.config.SessionSlotConfig;
import com.zerodhatech.models.Order; // Mock this
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EsmExecutionServiceTest {

    @Mock private KiteGateway kiteGateway;
    @Mock private DepthService depthService;
    @Mock private DepthStreamService depthStreamService;
    @Mock private AuctionAnalysisService auctionAnalysisService;
    @Mock private OrderAgeTracker orderAgeTracker;
    @Mock private ScheduleService scheduleService;
    @Mock private TradingScheduleRepository scheduleRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private IstClock istClock;
    @Mock private ExecutedTradeRepository executedTradeRepository;
    @Mock private EsmMonitorStateRepository esmMonitorStateRepository;
    @Mock private SessionSlotConfig sessionSlotConfig;
    @Mock private SettingsService settingsService;

    private EsmExecutionService esmExecutionService;
    private TradingSchedule schedule;
    private ZonedDateTime sessionStart;

    @BeforeEach
    void setUp() {
        esmExecutionService = new EsmExecutionService(
                kiteGateway, depthService, depthStreamService, auctionAnalysisService,
                orderAgeTracker, scheduleService, scheduleRepository, auditLogService,
                istClock, executedTradeRepository, esmMonitorStateRepository, sessionSlotConfig, settingsService
        );

        when(settingsService.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(settingsService.getLong(anyString(), anyLong())).thenAnswer(inv -> inv.getArgument(1));
        when(settingsService.getBoolean(anyString(), anyBoolean())).thenAnswer(inv -> inv.getArgument(1));

        when(esmMonitorStateRepository.findTopByScheduleIdAndActiveTrueOrderByUpdatedAtDesc(anyLong()))
                .thenReturn(java.util.Optional.empty());
        when(esmMonitorStateRepository.findTopByOrderIdAndActiveTrueOrderByUpdatedAtDesc(anyString()))
                .thenReturn(java.util.Optional.empty());

        // Setup common schedule
        sessionStart = ZonedDateTime.parse("2023-10-27T11:00:00+05:30[Asia/Kolkata]");
        schedule = new TradingSchedule();
        ReflectionTestUtils.setField(schedule, "id", 1L);
        schedule.setTradingsymbol("TEST-SYM");
        schedule.setQuantity(100);
        schedule.setSide(OrderSide.SELL);
        schedule.setNextExecutionTime(sessionStart);
        
        // Inject schedule into monitor
        esmExecutionService.monitor(schedule);
    }

    @Test
    void testPhantomWall_ImmediateExit() {
        // Time: T + 10m (Normal)
        when(istClock.now()).thenReturn(sessionStart.plusMinutes(10));
        
        DepthView depth = new DepthView();
        depth.setCapturedAt(sessionStart.plusMinutes(10));
        when(depthStreamService.getSnapshot(any())).thenReturn(depth);
        when(auctionAnalysisService.detectPhantomWall(depth)).thenReturn(true);
        when(kiteGateway.placePcaOrder(any())).thenReturn(CompletableFuture.completedFuture("ORDER_123"));

        esmExecutionService.runExecutionLoop();

        // Verify Exectuion
        verify(kiteGateway).placePcaOrder(schedule);
        verify(auditLogService).info(eq(schedule), eq("ESM Executed"), contains("[ESM_TRIGGER] Triggered by PHANTOM_WALL"));
    }

    @Test
    void testHazardPressure_Trigger() {
        // Time: T + 44m 30s (In Hazard Zone)
        when(istClock.now()).thenReturn(sessionStart.plusMinutes(44).plusSeconds(30));
        
        DepthView depth = new DepthView();
        depth.setCapturedAt(sessionStart.plusMinutes(44).plusSeconds(30));
        when(depthStreamService.getSnapshot(any())).thenReturn(depth);
        
        // Phantom Wall False
        when(auctionAnalysisService.detectPhantomWall(depth)).thenReturn(false);
        // Hazard Risk True
        when(auctionAnalysisService.getHazardRisk(depth, 100L)).thenReturn(true);
        
        when(kiteGateway.placePcaOrder(any())).thenReturn(CompletableFuture.completedFuture("ORDER_HAZARD"));

        esmExecutionService.runExecutionLoop();

        verify(kiteGateway).placePcaOrder(schedule);
        verify(auditLogService).info(eq(schedule), eq("ESM Executed"), contains("HAZARD_PRESSURE"));
    }

    @Test
    void testHazardTimeout_FalseAlarm_Cancel() {
        // 1. First Trigger Execution
        testHazardPressure_Trigger(); 
        
        // 2. Clear previous interactions to verify cancellation
        clearInvocations(kiteGateway, auditLogService);
        
        // 3. Advance Time to T + 44m 55s (Timeout)
        when(istClock.now()).thenReturn(sessionStart.plusMinutes(44).plusSeconds(55));
        
        // 4. Mock Market Safe Now
        DepthView depth = new DepthView();
        depth.setCapturedAt(sessionStart.plusMinutes(44).plusSeconds(55));
        when(depthStreamService.getSnapshot(any())).thenReturn(depth);
        when(auctionAnalysisService.getHazardRisk(depth, 100L)).thenReturn(false); // SAFE!
        
        // 5. Mock Cancel
        when(kiteGateway.cancelOrder("ORDER_HAZARD")).thenReturn(CompletableFuture.completedFuture(null));
        
        esmExecutionService.runExecutionLoop();
        
        verify(kiteGateway).cancelOrder("ORDER_HAZARD");
        verify(auditLogService).info(eq(schedule), eq("Defensive Cancel"), contains("[HAZARD_SAFE]"));
    }
    
    @Test
    void testSniperArming_OnSessionExpiry() {
        // Time: T + 45m 10s (Expired)
        when(istClock.now()).thenReturn(sessionStart.plusMinutes(45).plusSeconds(10));
        
        DepthView depth = new DepthView();
        depth.setCapturedAt(sessionStart.plusMinutes(45).plusSeconds(10));
        when(depthStreamService.getSnapshot(any())).thenReturn(depth);
        
        // Need Risk = High for Sniper
        when(auctionAnalysisService.getHazardRisk(depth, 100L)).thenReturn(true);
        
        schedule.setAutoRepeat(true); // Required for arming
        
        esmExecutionService.runExecutionLoop();
        
        // Verify Execution NOT called (expired)
        verify(kiteGateway, never()).placePcaOrder(any());
        
        // Verify scheduleService.markFailed is NOT called with "Skipped" (which implies safe expiry)
        // Or better: Verify we did NOT log "Price held up. No exit trigger."
        verify(auditLogService, never()).warn(any(), anyString(), contains("[NO_TRIGGER]"));
        
        // Verify aggressiveRolloverSchedules contains ID (using Reflection to peek private set)
        @SuppressWarnings("unchecked")
        java.util.Set<Long> snipers = (java.util.Set<Long>) ReflectionTestUtils.getField(esmExecutionService, "aggressiveRolloverSchedules");
        assert snipers != null;
        assert snipers.contains(1L);
    }
}
