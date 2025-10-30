package com.zerodhatech.models;

public class Quote {
    public QuoteDepth depth = new QuoteDepth();
    public double lastPrice;
    public double lastTradedPrice;
    public String instrumentToken;
    public double oi;
    public String timestamp;
    public double lowerCircuitLimit;
    public double upperCircuitLimit;
    public double oiDayHigh;
    public double oiDayLow;
    public Ohlc ohlc = new Ohlc();

    public static class Ohlc {
        public double open;
        public double high;
        public double low;
        public double close;
    }
}
