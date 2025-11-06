package com.exittrading.app.service;

import com.exittrading.app.domain.ScheduleStatus;
import com.exittrading.app.domain.SessionSlot;
import com.exittrading.app.domain.TradingSchedule;
import com.exittrading.app.domain.UserAccount;
import com.exittrading.app.dto.ScheduleRequest;
import com.exittrading.app.dto.ScheduleResponse;
import com.exittrading.app.repository.TradingScheduleRepository;
import com.exittrading.app.config.SessionSlotConfig;
import com.exittrading.app.repository.UserAccountRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class ScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd-MMM-yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final TradingScheduleRepository scheduleRepository;
    private final UserAccountRepository userRepository;
    private final IstClock clock;
    private final SessionSlotConfig slotConfig;

    public ScheduleService(TradingScheduleRepository scheduleRepository, UserAccountRepository userRepository, IstClock clock, SessionSlotConfig slotConfig) {
        this.scheduleRepository = scheduleRepository;
        this.userRepository = userRepository;
        this.clock = clock;
        this.slotConfig = slotConfig;
    }

    public List<ScheduleResponse> fetchScheduled(String username, List<ScheduleStatus> statuses) {
        UserAccount user = userRepository.findByUsername(username)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));
        return scheduleRepository.findByUserAndStatusIn(user, statuses)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public List<ScheduleResponse> fetchByStatus(ScheduleStatus status) {
        return scheduleRepository.findByStatus(status).stream().map(this::toResponse).collect(Collectors.toList());
    }

    public List<ScheduleResponse> fetchAllForUser(String username) {
        return userRepository.findByUsername(username)
                .map(u -> scheduleRepository.findByUser(u).stream().map(this::toResponse).collect(Collectors.toList()))
                .orElseGet(java.util.List::of);
    }

    @Transactional
    public ScheduleResponse createSchedule(String username, ScheduleRequest request) {
        UserAccount user = userRepository.findByUsername(username)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));
        TradingSchedule schedule = new TradingSchedule();
        schedule.setUser(user);
        schedule.setTradingsymbol(request.getTradingsymbol());
        schedule.setInstrumentToken(request.getInstrumentToken());
        schedule.setQuantity(request.getQuantity());
        schedule.setSide(request.getSide());
        schedule.setSessionSlot(request.getSessionSlot());
        schedule.setTradeDate(request.getTradeDate());
        schedule.setLimitPrice(request.getLimitPrice());
        schedule.setAutoRepeat(request.isAutoRepeat());
        schedule.setCancelOpenOrdersBeforeExecution(request.isCancelOpenOrdersBeforeExecution());
        schedule.setStatus(ScheduleStatus.SCHEDULED);
        var computed = computeNextAndAlign(request.getTradeDate(), request.getSessionSlot(), request.getScheduledTime());
        schedule.setNextExecutionTime(computed.time);
        if (computed.slot != null) schedule.setSessionSlot(computed.slot);
        schedule.setTradeDate(computed.time.toLocalDate());
        TradingSchedule saved = scheduleRepository.save(schedule);
        log.info("Scheduled req user={} symbol={} token={} side={} qty={} date={} slot={} manualTime={} next={} limit={} autoRepeat={} cancelBefore={}",
                username,
                schedule.getTradingsymbol(),
                schedule.getInstrumentToken(),
                schedule.getSide(),
                schedule.getQuantity(),
                schedule.getTradeDate(),
                schedule.getSessionSlot(),
                request.getScheduledTime(),
                schedule.getNextExecutionTime(),
                schedule.getLimitPrice(),
                schedule.isAutoRepeat(),
                schedule.isCancelOpenOrdersBeforeExecution());
        return toResponse(saved, computed.rolled);
    }

    @Transactional
    public ScheduleResponse updateSchedule(Long scheduleId, ScheduleRequest request) {
        TradingSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new EntityNotFoundException("Schedule not found"));
        schedule.setTradingsymbol(request.getTradingsymbol());
        schedule.setInstrumentToken(request.getInstrumentToken());
        schedule.setQuantity(request.getQuantity());
        schedule.setSide(request.getSide());
        schedule.setSessionSlot(request.getSessionSlot());
        schedule.setTradeDate(request.getTradeDate());
        schedule.setLimitPrice(request.getLimitPrice());
        schedule.setAutoRepeat(request.isAutoRepeat());
        schedule.setCancelOpenOrdersBeforeExecution(request.isCancelOpenOrdersBeforeExecution());
        var computed = computeNextAndAlign(request.getTradeDate(), request.getSessionSlot(), request.getScheduledTime());
        schedule.setNextExecutionTime(computed.time);
        if (computed.slot != null) schedule.setSessionSlot(computed.slot);
        schedule.setTradeDate(computed.time.toLocalDate());
        TradingSchedule saved = scheduleRepository.save(schedule);
        log.info("Rescheduled id={} symbol={} side={} qty={} date={} slot={} manualTime={} next={} limit={} autoRepeat={} cancelBefore={}",
                scheduleId,
                saved.getTradingsymbol(),
                saved.getSide(),
                saved.getQuantity(),
                saved.getTradeDate(),
                saved.getSessionSlot(),
                request.getScheduledTime(),
                saved.getNextExecutionTime(),
                saved.getLimitPrice(),
                saved.isAutoRepeat(),
                saved.isCancelOpenOrdersBeforeExecution());
        return toResponse(saved, computed.rolled);
    }

    @Transactional
    public void cancelSchedule(Long scheduleId) {
        TradingSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new EntityNotFoundException("Schedule not found"));
        schedule.setStatus(ScheduleStatus.CANCELLED);
        scheduleRepository.save(schedule);
        log.info("Cancelled schedule {}", scheduleId);
    }

    @Transactional
    public void markExecuted(TradingSchedule schedule, String message) {
        schedule.setStatus(ScheduleStatus.EXECUTED);
        schedule.setLastExecutedAt(clock.now());
        schedule.setLastExecutionMessage(message);
        schedule.setNextExecutionTime(null);
        scheduleRepository.save(schedule);
    }

    @Transactional
    public void markFailed(TradingSchedule schedule, String message) {
        schedule.setStatus(ScheduleStatus.FAILED);
        schedule.setLastExecutionMessage(message);
        schedule.setLastExecutedAt(clock.now());
        scheduleRepository.save(schedule);
    }

    @Transactional
    public TradingSchedule scheduleNextDay(TradingSchedule schedule) {
        TradingSchedule next = new TradingSchedule();
        next.setUser(schedule.getUser());
        next.setTradingsymbol(schedule.getTradingsymbol());
        next.setInstrumentToken(schedule.getInstrumentToken());
        next.setQuantity(schedule.getQuantity());
        next.setSide(schedule.getSide());
        next.setSessionSlot(schedule.getSessionSlot());
        next.setTradeDate(schedule.getTradeDate().plusDays(1));
        next.setLimitPrice(schedule.getLimitPrice());
        next.setAutoRepeat(schedule.isAutoRepeat());
        next.setCancelOpenOrdersBeforeExecution(schedule.isCancelOpenOrdersBeforeExecution());
        next.setStatus(ScheduleStatus.SCHEDULED);
        next.setNextExecutionTime(calculateNextExecutionTime(next.getTradeDate(), next.getSessionSlot(), null));
        return scheduleRepository.save(next);
    }

    public ZonedDateTime calculateNextExecutionTime(LocalDate date, SessionSlot session, java.time.LocalTime override) {
        java.time.LocalTime time = (override != null) ? override : session.getTime();
        ZonedDateTime now = clock.now();
        ZonedDateTime scheduled = ZonedDateTime.of(date, time, clock.zoneId());

        if (scheduled.isAfter(now)) {
            return scheduled;
        }

        // Auto-pick next slot from config if present; else use enum.
        LocalDate anchorDate = now.toLocalDate();
        java.time.LocalTime nowTime = now.toLocalTime();
        java.util.List<java.time.LocalTime> configured = (slotConfig != null) ? slotConfig.orderedTimes() : java.util.List.of();
        if (configured != null && !configured.isEmpty()) {
            for (java.time.LocalTime t : configured) {
                if (t.isAfter(nowTime)) return ZonedDateTime.of(anchorDate, t, clock.zoneId());
            }
            return ZonedDateTime.of(anchorDate.plusDays(1), configured.get(0), clock.zoneId());
        } else {
            for (SessionSlot s : SessionSlot.ordered()) {
                if (s.getTime().isAfter(nowTime)) return ZonedDateTime.of(anchorDate, s.getTime(), clock.zoneId());
            }
            SessionSlot first = SessionSlot.ordered().get(0);
            return ZonedDateTime.of(anchorDate.plusDays(1), first.getTime(), clock.zoneId());
        }
    }

    private static class ComputedNext {
        final ZonedDateTime time; final SessionSlot slot; final boolean rolled;
        ComputedNext(ZonedDateTime time, SessionSlot slot, boolean rolled){ this.time=time; this.slot=slot; this.rolled=rolled; }
    }

    private ComputedNext computeNextAndAlign(LocalDate date, SessionSlot session, java.time.LocalTime override){
        java.time.LocalTime desired = (override != null) ? override : session.getTime();
        ZonedDateTime now = clock.now();
        ZonedDateTime requested = ZonedDateTime.of(date, desired, clock.zoneId());

        java.util.List<java.time.LocalTime> configured = (slotConfig != null) ? slotConfig.orderedTimes() : java.util.List.of();
        boolean hasConfig = configured != null && !configured.isEmpty();

        if (requested.isAfter(now)) {
            return new ComputedNext(requested, resolveEnumSlot(desired), false);
        }

        java.time.LocalDate anchor = now.toLocalDate();
        java.time.LocalTime nowTime = now.toLocalTime();
        if (hasConfig) {
            for (java.time.LocalTime t : configured) {
                if (t.isAfter(nowTime)) {
                    return new ComputedNext(ZonedDateTime.of(anchor, t, clock.zoneId()), resolveEnumSlot(t), true);
                }
            }
            java.time.LocalTime first = configured.get(0);
            return new ComputedNext(ZonedDateTime.of(anchor.plusDays(1), first, clock.zoneId()), resolveEnumSlot(first), true);
        } else {
            for (SessionSlot s : SessionSlot.ordered()) {
                if (s.getTime().isAfter(nowTime)) {
                    return new ComputedNext(ZonedDateTime.of(anchor, s.getTime(), clock.zoneId()), s, true);
                }
            }
            SessionSlot first = SessionSlot.ordered().get(0);
            return new ComputedNext(ZonedDateTime.of(anchor.plusDays(1), first.getTime(), clock.zoneId()), first, true);
        }
    }

    private SessionSlot resolveEnumSlot(java.time.LocalTime t){
        for (SessionSlot s : SessionSlot.ordered()){
            if (s.getTime().equals(t)) return s;
        }
        return null;
    }

    public TradingSchedule loadEntity(Long scheduleId) {
        return scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new EntityNotFoundException("Schedule not found"));
    }

    public ScheduleResponse toResponse(TradingSchedule schedule) { return toResponse(schedule, false); }

    public ScheduleResponse toResponse(TradingSchedule schedule, boolean rolled) {
        ScheduleResponse response = new ScheduleResponse();
        response.setId(schedule.getId());
        response.setUsername(schedule.getUser().getUsername());
        response.setTradingsymbol(schedule.getTradingsymbol());
        response.setInstrumentToken(schedule.getInstrumentToken());
        response.setQuantity(schedule.getQuantity());
        response.setSide(schedule.getSide());
        response.setStatus(schedule.getStatus());
        response.setSessionSlot(schedule.getSessionSlot());
        // Show actual scheduled time (manual or slot time)
        response.setSessionTimeIst(schedule.getNextExecutionTime() != null ? schedule.getNextExecutionTime().toLocalTime().format(TIME_FMT) : schedule.getSessionSlot().getTime().format(TIME_FMT));
        response.setTradeDateIst(schedule.getTradeDate().format(DATE_FMT));
        response.setNextExecutionTime(schedule.getNextExecutionTime());
        response.setLastExecutedAt(schedule.getLastExecutedAt());
        response.setLastExecutionMessage(schedule.getLastExecutionMessage());
        response.setLimitPrice(schedule.getLimitPrice());
        response.setAutoRepeat(schedule.isAutoRepeat());
        response.setCancelOpenOrdersBeforeExecution(schedule.isCancelOpenOrdersBeforeExecution());
        response.setRolledToNextSlot(rolled);
        if (schedule.getNextExecutionTime() != null) {
            response.setRolledTimeIst(schedule.getNextExecutionTime().toLocalTime().format(TIME_FMT));
        }
        return response;
    }
}
