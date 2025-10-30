package com.zerodhatech.models;

import java.util.ArrayList;
import java.util.List;

public class GTTParams {
    public String triggerType;
    public String exchange;
    public String tradingsymbol;
    public double lastPrice;
    public List<Double> triggerPrices = new ArrayList<>();
    public List<GTTOrderParams> orders = new ArrayList<>();

    public class GTTOrderParams {
        public String orderType;
        public double price;
        public String product;
        public String transactionType;
        public int quantity;
    }
}
