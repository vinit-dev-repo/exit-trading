package com.exittrading.app.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "market_snapshots")
@IdClass(SnapshotKey.class)
public class MarketSnapshot {

    @Id
    private ZonedDateTime capturedAt;

    @Id
    private Long token;

    @Column(nullable = false)
    private String symbol;

    private Double ltp;
    private Long ltq;
    private String ltt;
    private Long volume;

    private Double openPrice;
    private Double highPrice;
    private Double lowPrice;
    private Double prevClose;
    private Double lowerCircuit;
    private Double upperCircuit;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> bids;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> asks;

    // Getters and Setters

    public ZonedDateTime getCapturedAt() { return capturedAt; }
    public void setCapturedAt(ZonedDateTime capturedAt) { this.capturedAt = capturedAt; }

    public Long getToken() { return token; }
    public void setToken(Long token) { this.token = token; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public Double getLtp() { return ltp; }
    public void setLtp(Double ltp) { this.ltp = ltp; }

    public Long getLtq() { return ltq; }
    public void setLtq(Long ltq) { this.ltq = ltq; }

    public String getLtt() { return ltt; }
    public void setLtt(String ltt) { this.ltt = ltt; }

    public Long getVolume() { return volume; }
    public void setVolume(Long volume) { this.volume = volume; }

    public Double getOpenPrice() { return openPrice; }
    public void setOpenPrice(Double openPrice) { this.openPrice = openPrice; }

    public Double getHighPrice() { return highPrice; }
    public void setHighPrice(Double highPrice) { this.highPrice = highPrice; }

    public Double getLowPrice() { return lowPrice; }
    public void setLowPrice(Double lowPrice) { this.lowPrice = lowPrice; }

    public Double getPrevClose() { return prevClose; }
    public void setPrevClose(Double prevClose) { this.prevClose = prevClose; }

    public Double getLowerCircuit() { return lowerCircuit; }
    public void setLowerCircuit(Double lowerCircuit) { this.lowerCircuit = lowerCircuit; }

    public Double getUpperCircuit() { return upperCircuit; }
    public void setUpperCircuit(Double upperCircuit) { this.upperCircuit = upperCircuit; }

    public List<Map<String, Object>> getBids() { return bids; }
    public void setBids(List<Map<String, Object>> bids) { this.bids = bids; }

    public List<Map<String, Object>> getAsks() { return asks; }
    public void setAsks(List<Map<String, Object>> asks) { this.asks = asks; }
}
