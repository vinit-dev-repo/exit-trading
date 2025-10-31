package com.exittrading.app.dto;

import com.exittrading.app.domain.OrderSide;
import com.exittrading.app.domain.SessionSlot;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

public class ScheduleRequest {

    @NotBlank
    private String tradingsymbol;

    @NotBlank
    private String instrumentToken;

    @Min(1)
    private int quantity;

    @NotNull
    private OrderSide side;

    @NotNull
    private SessionSlot sessionSlot;

    @NotNull
    private LocalDate tradeDate;

    private BigDecimal limitPrice;

    private boolean autoRepeat;

    private boolean cancelOpenOrdersBeforeExecution = true;

    // Optional manual scheduling override (IST)
    private LocalTime scheduledTime;

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

    public LocalTime getScheduledTime() { return scheduledTime; }
    public void setScheduledTime(LocalTime scheduledTime) { this.scheduledTime = scheduledTime; }
}
