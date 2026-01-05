package com.exittrading.app.dto;

public class ScripAnalysis {
    public String originalSymbol;
    public String exchange;
    public String suggestedSymbol;
    public String token;
    public String status; // "FOUND", "UNKNOWN"

    public ScripAnalysis() {}

    public ScripAnalysis(String originalSymbol, String exchange, String suggestedSymbol, String token, String status) {
        this.originalSymbol = originalSymbol;
        this.exchange = exchange;
        this.suggestedSymbol = suggestedSymbol;
        this.token = token;
        this.status = status;
    }
}
