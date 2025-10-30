package com.zerodhatech.models;

public class Holding {
    public String tradingSymbol;
    public double dayChange;
    public double dayChangePercentage;
    public MTF mtf = new MTF();

    public static class MTF {
        public int quantity;
        public double averagePrice;
    }
}
