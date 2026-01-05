package com.exittrading.app.domain;

import jakarta.persistence.*;
import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Table(name = "executed_trades")
public class ExecutedTrade {

    @Id
    private UUID tradeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id")
    private TradingSchedule schedule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private UserAccount user;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private String side;

    private Integer filledQty;
    private Double filledPrice;
    private String exchangeOrderId;
    private ZonedDateTime executionTime = ZonedDateTime.now();
    private String status;
    private String remarks;

    public ExecutedTrade() {
        this.tradeId = UUID.randomUUID();
    }

    // Getters and Setters

    public UUID getTradeId() { return tradeId; }
    public void setTradeId(UUID tradeId) { this.tradeId = tradeId; }

    public TradingSchedule getSchedule() { return schedule; }
    public void setSchedule(TradingSchedule schedule) { this.schedule = schedule; }

    public UserAccount getUser() { return user; }
    public void setUser(UserAccount user) { this.user = user; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }

    public Integer getFilledQty() { return filledQty; }
    public void setFilledQty(Integer filledQty) { this.filledQty = filledQty; }

    public Double getFilledPrice() { return filledPrice; }
    public void setFilledPrice(Double filledPrice) { this.filledPrice = filledPrice; }

    public String getExchangeOrderId() { return exchangeOrderId; }
    public void setExchangeOrderId(String exchangeOrderId) { this.exchangeOrderId = exchangeOrderId; }

    public ZonedDateTime getExecutionTime() { return executionTime; }
    public void setExecutionTime(ZonedDateTime executionTime) { this.executionTime = executionTime; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getRemarks() { return remarks; }
    public void setRemarks(String remarks) { this.remarks = remarks; }
}
