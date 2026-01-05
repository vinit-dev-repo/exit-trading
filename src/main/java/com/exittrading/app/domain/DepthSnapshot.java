package com.exittrading.app.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.ZonedDateTime;

/**
 * Entity representing a snapshot of market depth.
 * Used for storing historical market data for analysis and ML training.
 */
@Entity
@Table(name = "depth_snapshots")
public class DepthSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private UserAccount user;

    @Column(nullable = false)
    private String tradingsymbol;

    // ----- Core Market Data -----
    private BigDecimal ltp;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal prevClose;
    private Long volume;
    private BigDecimal avgPrice;
    private BigDecimal lowerCircuit;
    private BigDecimal upperCircuit;

    // ----- Timestamps & Quantities -----
    private ZonedDateTime capturedAt;
    private String lastTradeTime; // Store as String or parsed ZDT

    private long buyQuantity;
    private long sellQuantity;
    private Long lastTradedQuantity; // ltq

    // ----- Order Book (JSON) -----
    @Column(columnDefinition = "TEXT") // Use TEXT for large JSON strings
    private String buyLevels; // JSON list of top 5 bids

    @Column(columnDefinition = "TEXT")
    private String sellLevels; // JSON list of top 5 asks

    public Long getId() { return id; }
    public UserAccount getUser() { return user; }
    public void setUser(UserAccount user) { this.user = user; }
    public String getTradingsymbol() { return tradingsymbol; }
    public void setTradingsymbol(String tradingsymbol) { this.tradingsymbol = tradingsymbol; }
    public BigDecimal getLtp() { return ltp; }
    public void setLtp(BigDecimal ltp) { this.ltp = ltp; }
    public BigDecimal getOpen() { return open; }
    public void setOpen(BigDecimal open) { this.open = open; }
    public BigDecimal getHigh() { return high; }
    public void setHigh(BigDecimal high) { this.high = high; }
    public BigDecimal getLow() { return low; }
    public void setLow(BigDecimal low) { this.low = low; }
    public BigDecimal getPrevClose() { return prevClose; }
    public void setPrevClose(BigDecimal prevClose) { this.prevClose = prevClose; }
    public Long getVolume() { return volume; }
    public void setVolume(Long volume) { this.volume = volume; }
    public BigDecimal getAvgPrice() { return avgPrice; }
    public void setAvgPrice(BigDecimal avgPrice) { this.avgPrice = avgPrice; }
    public BigDecimal getLowerCircuit() { return lowerCircuit; }
    public void setLowerCircuit(BigDecimal lowerCircuit) { this.lowerCircuit = lowerCircuit; }
    public BigDecimal getUpperCircuit() { return upperCircuit; }
    public void setUpperCircuit(BigDecimal upperCircuit) { this.upperCircuit = upperCircuit; }
    public ZonedDateTime getCapturedAt() { return capturedAt; }
    public void setCapturedAt(ZonedDateTime capturedAt) { this.capturedAt = capturedAt; }
    public String getLastTradeTime() { return lastTradeTime; }
    public void setLastTradeTime(String lastTradeTime) { this.lastTradeTime = lastTradeTime; }
    public long getBuyQuantity() { return buyQuantity; }
    public void setBuyQuantity(long buyQuantity) { this.buyQuantity = buyQuantity; }
    public long getSellQuantity() { return sellQuantity; }
    public void setSellQuantity(long sellQuantity) { this.sellQuantity = sellQuantity; }
    public Long getLastTradedQuantity() { return lastTradedQuantity; }
    public void setLastTradedQuantity(Long lastTradedQuantity) { this.lastTradedQuantity = lastTradedQuantity; }
    public String getBuyLevels() { return buyLevels; }
    public void setBuyLevels(String buyLevels) { this.buyLevels = buyLevels; }
    public String getSellLevels() { return sellLevels; }
    public void setSellLevels(String sellLevels) { this.sellLevels = sellLevels; }
}
