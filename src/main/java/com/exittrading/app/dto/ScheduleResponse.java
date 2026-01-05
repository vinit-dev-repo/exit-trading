package com.exittrading.app.dto;

import com.exittrading.app.domain.OrderSide;
import com.exittrading.app.domain.ScheduleStatus;
import com.exittrading.app.domain.SessionSlot;

import java.math.BigDecimal;
import java.time.ZonedDateTime;

/**
 * DTO for displaying trading schedule details to the client.
 */
public class ScheduleResponse {
    private Long id;
    private String username;
    private String tradingsymbol;
    private String instrumentToken;
    private int quantity;
    private OrderSide side;
    private ScheduleStatus status;
    private SessionSlot sessionSlot;
    private String sessionTimeIst;
    private String tradeDateIst;
    private ZonedDateTime nextExecutionTime;
    private ZonedDateTime lastExecutedAt;
    private String lastExecutionMessage;
    private BigDecimal limitPrice;
    private boolean autoRepeat;
    private boolean cancelOpenOrdersBeforeExecution;
    // Hints for UI
    private boolean rolledToNextSlot;
    private String rolledTimeIst;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
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

    public String getSessionTimeIst() {
        return sessionTimeIst;
    }

    public void setSessionTimeIst(String sessionTimeIst) {
        this.sessionTimeIst = sessionTimeIst;
    }

    public String getTradeDateIst() {
        return tradeDateIst;
    }

    public void setTradeDateIst(String tradeDateIst) {
        this.tradeDateIst = tradeDateIst;
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

    public boolean isRolledToNextSlot() { return rolledToNextSlot; }
    public void setRolledToNextSlot(boolean rolledToNextSlot) { this.rolledToNextSlot = rolledToNextSlot; }
    public String getRolledTimeIst() { return rolledTimeIst; }
    public void setRolledTimeIst(String rolledTimeIst) { this.rolledTimeIst = rolledTimeIst; }
}
