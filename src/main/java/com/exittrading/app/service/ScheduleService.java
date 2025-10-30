package com.exittrading.app.service;

import com.exittrading.app.domain.ScheduleStatus;
import com.exittrading.app.domain.SessionSlot;
import com.exittrading.app.domain.TradingSchedule;
import com.exittrading.app.domain.UserAccount;
import com.exittrading.app.dto.ScheduleRequest;
import com.exittrading.app.dto.ScheduleResponse;
import com.exittrading.app.repository.TradingScheduleRepository;
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

    public ScheduleService(TradingScheduleRepository scheduleRepository, UserAccountRepository userRepository, IstClock clock) {
        this.scheduleRepository = scheduleRepository;
        this.userRepository = userRepository;
        this.clock = clock;
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
        UserAccount user = userRepository.findByUsername(username)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));
        return scheduleRepository.findByUser(user).stream().map(this::toResponse).collect(Collectors.toList());
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
        schedule.setNextExecutionTime(calculateNextExecutionTime(request.getTradeDate(), request.getSessionSlot()));
        TradingSchedule saved = scheduleRepository.save(schedule);
        log.info("Scheduled {} {} for {} at {}", schedule.getSide(), schedule.getQuantity(), schedule.getTradingsymbol(), schedule.getSessionSlot());
        return toResponse(saved);
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
        schedule.setNextExecutionTime(calculateNextExecutionTime(request.getTradeDate(), request.getSessionSlot()));
        return toResponse(scheduleRepository.save(schedule));
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
        next.setNextExecutionTime(calculateNextExecutionTime(next.getTradeDate(), next.getSessionSlot()));
        return scheduleRepository.save(next);
    }

    public ZonedDateTime calculateNextExecutionTime(LocalDate date, SessionSlot session) {
        ZonedDateTime scheduled = ZonedDateTime.of(date, session.getTime(), clock.zoneId());
        if (scheduled.isBefore(clock.now())) {
            throw new IllegalArgumentException("Scheduled time is in the past");
        }
        return scheduled;
    }

    public TradingSchedule loadEntity(Long scheduleId) {
        return scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new EntityNotFoundException("Schedule not found"));
    }

    public ScheduleResponse toResponse(TradingSchedule schedule) {
        ScheduleResponse response = new ScheduleResponse();
        response.setId(schedule.getId());
        response.setUsername(schedule.getUser().getUsername());
        response.setTradingsymbol(schedule.getTradingsymbol());
        response.setInstrumentToken(schedule.getInstrumentToken());
        response.setQuantity(schedule.getQuantity());
        response.setSide(schedule.getSide());
        response.setStatus(schedule.getStatus());
        response.setSessionSlot(schedule.getSessionSlot());
        response.setSessionTimeIst(schedule.getSessionSlot().getTime().format(TIME_FMT));
        response.setTradeDateIst(schedule.getTradeDate().format(DATE_FMT));
        response.setNextExecutionTime(schedule.getNextExecutionTime());
        response.setLastExecutedAt(schedule.getLastExecutedAt());
        response.setLastExecutionMessage(schedule.getLastExecutionMessage());
        response.setLimitPrice(schedule.getLimitPrice());
        response.setAutoRepeat(schedule.isAutoRepeat());
        response.setCancelOpenOrdersBeforeExecution(schedule.isCancelOpenOrdersBeforeExecution());
        return response;
    }
}
