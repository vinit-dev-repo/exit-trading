package com.exittrading.app.dto;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * DTO representing a market depth snapshot.
 * Includes top 5 bids/asks and other market statistics.
 */
public class DepthView {
    private String tradingsymbol;
    private long buyQuantity;
    private long sellQuantity;
    private BigDecimal ltp;
    private ZonedDateTime capturedAt;

    // Optional level-by-level depth for UI (top 5)
    public static class Level {
        private int quantity;
        private double price;
        private int orders;

        public Level() {}

        public Level(int quantity, double price, int orders) {
            this.quantity = quantity;
            this.price = price;
            this.orders = orders;
        }

        public int getQuantity() {
            return quantity;
        }

        public void setQuantity(int quantity) {
            this.quantity = quantity;
        }

        public double getPrice() {
            return price;
        }

        public void setPrice(double price) {
            this.price = price;
        }

        public int getOrders() {
            return orders;
        }

        public void setOrders(int orders) {
            this.orders = orders;
        }
    }

    private List<Level> buyLevels = new ArrayList<>();
    private List<Level> sellLevels = new ArrayList<>();

    // Optional quote summary fields
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal prevClose;
    private Long volume;
    private BigDecimal avgPrice;
    private BigDecimal lowerCircuit;
    private BigDecimal upperCircuit;
    private Long ltq; // last traded quantity
    private String ltt; // last traded time as string
    // Optional metadata and derived metrics
    private Double tick;                 // true tick size if available
    private Long exitQuantity;           // position size to stress-test impact
    private Double driftBps;             // price drift toward band (bps per second)
    private Double ltqPerSec;            // trade velocity (shares per second)
    private Double timeToBandSellSec;    // estimated seconds to hit lower band
    private Double timeToBandBuySec;     // estimated seconds to hit upper band
    private Long maxBuyOrderQty;         // largest single order on bid side
    private Long maxSellOrderQty;        // largest single order on ask side
    private Integer maxBuyOrderCount;    // number of orders at that max bid level
    private Integer maxSellOrderCount;   // number of orders at that max ask level
    private Double maxBuyOrderPrice;     // price of the max bid level
    private Double maxSellOrderPrice;    // price of the max ask level

    public String getTradingsymbol() {
        return tradingsymbol;
    }

    public void setTradingsymbol(String tradingsymbol) {
        this.tradingsymbol = tradingsymbol;
    }

    public long getBuyQuantity() {
        return buyQuantity;
    }

    public void setBuyQuantity(long buyQuantity) {
        this.buyQuantity = buyQuantity;
    }

    public long getSellQuantity() {
        return sellQuantity;
    }

    public void setSellQuantity(long sellQuantity) {
        this.sellQuantity = sellQuantity;
    }

    public BigDecimal getLtp() {
        return ltp;
    }

    public void setLtp(BigDecimal ltp) {
        this.ltp = ltp;
    }

    public ZonedDateTime getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(ZonedDateTime capturedAt) {
        this.capturedAt = capturedAt;
    }

    public List<Level> getBuyLevels() { return buyLevels; }
    public void setBuyLevels(List<Level> buyLevels) { this.buyLevels = buyLevels; }
    public List<Level> getSellLevels() { return sellLevels; }
    public void setSellLevels(List<Level> sellLevels) { this.sellLevels = sellLevels; }

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
    public Long getLtq() { return ltq; }
    public void setLtq(Long ltq) { this.ltq = ltq; }
    public String getLtt() { return ltt; }
    public void setLtt(String ltt) { this.ltt = ltt; }

    public Double getTick() { return tick; }
    public void setTick(Double tick) { this.tick = tick; }

    public Long getExitQuantity() { return exitQuantity; }
    public void setExitQuantity(Long exitQuantity) { this.exitQuantity = exitQuantity; }

    public Double getDriftBps() { return driftBps; }
    public void setDriftBps(Double driftBps) { this.driftBps = driftBps; }

    public Double getLtqPerSec() { return ltqPerSec; }
    public void setLtqPerSec(Double ltqPerSec) { this.ltqPerSec = ltqPerSec; }

    public Double getTimeToBandSellSec() { return timeToBandSellSec; }
    public void setTimeToBandSellSec(Double timeToBandSellSec) { this.timeToBandSellSec = timeToBandSellSec; }

    public Double getTimeToBandBuySec() { return timeToBandBuySec; }
    public void setTimeToBandBuySec(Double timeToBandBuySec) { this.timeToBandBuySec = timeToBandBuySec; }

    public Long getMaxBuyOrderQty() { return maxBuyOrderQty; }
    public void setMaxBuyOrderQty(Long maxBuyOrderQty) { this.maxBuyOrderQty = maxBuyOrderQty; }

    public Long getMaxSellOrderQty() { return maxSellOrderQty; }
    public void setMaxSellOrderQty(Long maxSellOrderQty) { this.maxSellOrderQty = maxSellOrderQty; }

    public Integer getMaxBuyOrderCount() { return maxBuyOrderCount; }
    public void setMaxBuyOrderCount(Integer maxBuyOrderCount) { this.maxBuyOrderCount = maxBuyOrderCount; }

    public Integer getMaxSellOrderCount() { return maxSellOrderCount; }
    public void setMaxSellOrderCount(Integer maxSellOrderCount) { this.maxSellOrderCount = maxSellOrderCount; }

    public Double getMaxBuyOrderPrice() { return maxBuyOrderPrice; }
    public void setMaxBuyOrderPrice(Double maxBuyOrderPrice) { this.maxBuyOrderPrice = maxBuyOrderPrice; }

    public Double getMaxSellOrderPrice() { return maxSellOrderPrice; }
    public void setMaxSellOrderPrice(Double maxSellOrderPrice) { this.maxSellOrderPrice = maxSellOrderPrice; }
}
