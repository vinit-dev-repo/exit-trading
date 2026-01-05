package com.exittrading.app.dto;

/**
 * DTO for displaying major ESM flags and indicator values in the UI.
 */
public class EsmFlagSummary {
    public String symbol;
    public Double lastTradedPrice;
    public Double openAtSessionStart;
    public Double previousClose;
    public Double upperCircuit;
    public Double limitPrice;
    public Double equilibriumPriceRaw;
    public Double equilibriumPriceRobust;
    public Double orderBookImbalance;
    public Double trendDeltaTpm;
    public Double sessionDrift;
    public Boolean downsideGateActive;
    public String downsideGateReason;
    public Boolean upperCircuitLock;
    public Boolean upperCircuitBreakExpected;
}
