package com.exittrading.app.dto;

public class LiquidityImpactResult {
    private String symbol;
    private String stage; // ESM-1 | ESM-2 (optional incoming override)
    private String legend; // Low | Moderate | High (summary bucket)
    private String note;   // free-form short note

    public LiquidityImpactResult() {}

    public LiquidityImpactResult(String symbol, String stage, String legend, String note) {
        this.symbol = symbol;
        this.stage = stage;
        this.legend = legend;
        this.note = note;
    }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }

    public String getLegend() { return legend; }
    public void setLegend(String legend) { this.legend = legend; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}

