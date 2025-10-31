package com.exittrading.app.dto;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

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
}
