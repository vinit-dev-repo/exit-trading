package com.exittrading.app.dto;

import java.util.Map;

public class LiquidityImpactResponse {
    public static class Best {
        public Double bid;
        public Double ask;
        public Double spread;
    }
    public static class Band {
        public Double L;
        public Double U;
    }
    public static class DeltaPoint {
        public Integer shares; // null when not computable
        public Double price;   // resulting price (clamped to band)
    }
    public static class SideDeltas {
        public DeltaPoint oneTick;
        public DeltaPoint toBand;
    }
    public static class Deltas {
        public SideDeltas sell;
        public SideDeltas buy;
    }
    public static class Scores {
        public Double sell;
        public Double buy;
        public String legend;
    }

    public static class Micro {
        public Double obiPct;       // + means bid-heavy, - ask-heavy (top-5)
        public Double swingSell;    // short-horizon sell-side swing [0..1]
        public Double microSell;    // normalized microstructure score [0..1]
        public Boolean dumpSell;    // decision flag
        public String dumpReason;   // brief reason for flag
    }

    public static class Combined {
        public Double sell;
        public Double buy;
        public String legend;
    }

    public String symbol;
    public Double tick;
    public Best best;
    public Band band;
    public String stage;        // ESM-1 | ESM-2 (inferred or provided)
    public String auctionPhase; // entry|buffer|uncross|continuous (optional)
    public Long qref;
    public Deltas deltas;
    public Scores scores;
    public Micro micro;       // microstructure context and dump flag
    public Combined combined; // combined score using LI + micro inputs
    public String notes;  // e.g., partial depth, tick, spread, stage
    public String human;  // optional human-readable multi-line block
    // Derived live metrics (optional)
    public Double driftBps;
    public Double ltqPerSec;
    public Double timeToBandSellSec;
    public Double timeToBandBuySec;
    public Double depthConfidence;
    public Long maxBuyOrderQty;
    public Long maxSellOrderQty;
    public Integer maxBuyOrderCount;
    public Integer maxSellOrderCount;
    public Double maxBuyOrderPrice;
    public Double maxSellOrderPrice;
}
