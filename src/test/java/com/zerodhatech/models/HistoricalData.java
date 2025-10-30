package com.zerodhatech.models;

import java.util.ArrayList;
import java.util.List;

public class HistoricalData {
    public final List<QuoteData> dataArrayList = new ArrayList<>();

    public static class QuoteData {
        public double volume;
        public double oi;
    }
}
