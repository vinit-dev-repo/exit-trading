package com.zerodhatech.models;

import java.util.ArrayList;
import java.util.List;

public class GTT {
    public int id;
    public String createdAt;
    public Condition condition = new Condition();
    public List<GTTParams.GTTOrderParams> orders = new ArrayList<>();

    public static class Condition {
        public String exchange;
        public String tradingSymbol;
    }
}
