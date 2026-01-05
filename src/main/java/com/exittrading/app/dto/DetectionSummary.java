package com.exittrading.app.dto;

/**
 * DTO summarizing the results of spoofing or pressure detection analysis.
 */
public class DetectionSummary {
    private String tradingsymbol;
    private Double obi;               // order book imbalance [-1..+1]
    private Double swing;             // pressure swing vs EWMA
    private Double sellSpikeScore;    // placeholder score
    private Boolean confirmed;        // dump signal confirmed
    private Boolean likelySpoof;      // spoof suspicion
    private Double recommendedLimit;  // suggested exit price (optional)

    public String getTradingsymbol() { return tradingsymbol; }
    public void setTradingsymbol(String tradingsymbol) { this.tradingsymbol = tradingsymbol; }

    public Double getObi() { return obi; }
    public void setObi(Double obi) { this.obi = obi; }

    public Double getSwing() { return swing; }
    public void setSwing(Double swing) { this.swing = swing; }

    public Double getSellSpikeScore() { return sellSpikeScore; }
    public void setSellSpikeScore(Double sellSpikeScore) { this.sellSpikeScore = sellSpikeScore; }

    public Boolean getConfirmed() { return confirmed; }
    public void setConfirmed(Boolean confirmed) { this.confirmed = confirmed; }

    public Boolean getLikelySpoof() { return likelySpoof; }
    public void setLikelySpoof(Boolean likelySpoof) { this.likelySpoof = likelySpoof; }

    public Double getRecommendedLimit() { return recommendedLimit; }
    public void setRecommendedLimit(Double recommendedLimit) { this.recommendedLimit = recommendedLimit; }
}

