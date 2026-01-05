package com.exittrading.app.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;

/**
 * Entity representing a scheduled trade.
 * Contains all parameters required to place an order at a future time.
 */
@Entity
@Table(name = "trading_schedules")
public class TradingSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "schedule_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    @Column(name = "symbol", nullable = false)
    private String tradingsymbol;

    @Column(nullable = false)
    private String instrumentToken;

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderSide side;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScheduleStatus status = ScheduleStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SessionSlot sessionSlot;

    @Column(nullable = false)
    private LocalDate tradeDate;

    @Column(name = "next_execution")
    private ZonedDateTime nextExecutionTime;

    private ZonedDateTime lastExecutedAt;

    @Column(name = "last_message")
    private String lastExecutionMessage;

    private BigDecimal limitPrice;

    private boolean autoRepeat;

    @Column(name = "cancel_open_orders")
    private boolean cancelOpenOrdersBeforeExecution = true;

    public Long getId() {
        return id;
    }

    public UserAccount getUser() {
        return user;
    }

    public void setUser(UserAccount user) {
        this.user = user;
    }

    public String getTradingsymbol() {
        return tradingsymbol;
    }

    public void setTradingsymbol(String tradingsymbol) {
        this.tradingsymbol = tradingsymbol;
    }

    public String getInstrumentToken() {
        return instrumentToken;
    }

    public void setInstrumentToken(String instrumentToken) {
        this.instrumentToken = instrumentToken;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public OrderSide getSide() {
        return side;
    }

    public void setSide(OrderSide side) {
        this.side = side;
    }

    public ScheduleStatus getStatus() {
        return status;
    }

    public void setStatus(ScheduleStatus status) {
        this.status = status;
    }

    public SessionSlot getSessionSlot() {
        return sessionSlot;
    }

    public void setSessionSlot(SessionSlot sessionSlot) {
        this.sessionSlot = sessionSlot;
    }

    public LocalDate getTradeDate() {
        return tradeDate;
    }

    public void setTradeDate(LocalDate tradeDate) {
        this.tradeDate = tradeDate;
    }

    public ZonedDateTime getNextExecutionTime() {
        return nextExecutionTime;
    }

    public void setNextExecutionTime(ZonedDateTime nextExecutionTime) {
        this.nextExecutionTime = nextExecutionTime;
    }

    public ZonedDateTime getLastExecutedAt() {
        return lastExecutedAt;
    }

    public void setLastExecutedAt(ZonedDateTime lastExecutedAt) {
        this.lastExecutedAt = lastExecutedAt;
    }

    public String getLastExecutionMessage() {
        return lastExecutionMessage;
    }

    public void setLastExecutionMessage(String lastExecutionMessage) {
        this.lastExecutionMessage = lastExecutionMessage;
    }

    public BigDecimal getLimitPrice() {
        return limitPrice;
    }

    public void setLimitPrice(BigDecimal limitPrice) {
        this.limitPrice = limitPrice;
    }

    public boolean isAutoRepeat() {
        return autoRepeat;
    }

    public void setAutoRepeat(boolean autoRepeat) {
        this.autoRepeat = autoRepeat;
    }

    public boolean isCancelOpenOrdersBeforeExecution() {
        return cancelOpenOrdersBeforeExecution;
    }

    public void setCancelOpenOrdersBeforeExecution(boolean cancelOpenOrdersBeforeExecution) {
        this.cancelOpenOrdersBeforeExecution = cancelOpenOrdersBeforeExecution;
    }
}
