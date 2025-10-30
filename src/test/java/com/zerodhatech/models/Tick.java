package com.zerodhatech.models;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Tick {
    private double lastTradedPrice;
    private double oi;
    private double openInterestDayHigh;
    private double openInterestDayLow;
    private double change;
    private long tickTimestamp;
    private long lastTradedTime;
    private final Map<String, List<DepthLevel>> marketDepth = new HashMap<>();

    public Tick() {
        marketDepth.put("buy", new ArrayList<>());
        marketDepth.put("sell", new ArrayList<>());
    }

    public double getLastTradedPrice() {
        return lastTradedPrice;
    }

    public void setLastTradedPrice(double lastTradedPrice) {
        this.lastTradedPrice = lastTradedPrice;
    }

    public double getOi() {
        return oi;
    }

    public void setOi(double oi) {
        this.oi = oi;
    }

    public double getOpenInterestDayHigh() {
        return openInterestDayHigh;
    }

    public void setOpenInterestDayHigh(double openInterestDayHigh) {
        this.openInterestDayHigh = openInterestDayHigh;
    }

    public double getOpenInterestDayLow() {
        return openInterestDayLow;
    }

    public void setOpenInterestDayLow(double openInterestDayLow) {
        this.openInterestDayLow = openInterestDayLow;
    }

    public double getChange() {
        return change;
    }

    public void setChange(double change) {
        this.change = change;
    }

    public long getTickTimestamp() {
        return tickTimestamp;
    }

    public void setTickTimestamp(long tickTimestamp) {
        this.tickTimestamp = tickTimestamp;
    }

    public long getLastTradedTime() {
        return lastTradedTime;
    }

    public void setLastTradedTime(long lastTradedTime) {
        this.lastTradedTime = lastTradedTime;
    }

    public Map<String, List<DepthLevel>> getMarketDepth() {
        return marketDepth;
    }
}
