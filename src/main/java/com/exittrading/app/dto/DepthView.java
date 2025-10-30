package com.exittrading.app.dto;

import java.math.BigDecimal;
import java.time.ZonedDateTime;

public class DepthView {
    private String tradingsymbol;
    private long buyQuantity;
    private long sellQuantity;
    private BigDecimal ltp;
    private ZonedDateTime capturedAt;

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
}
