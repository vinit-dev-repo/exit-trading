package com.exittrading.app.domain;

import jakarta.persistence.*;
import java.time.ZonedDateTime;

@Entity
@Table(name = "instruments")
public class Instrument {

    @Id
    private Long token;

    @Column(nullable = false)
    private String symbol;

    private String exchange;
    
    private String name;
    
    private String industry;
    
    private Double faceValue;
    
    private String sourceUrl;

    private ZonedDateTime updatedAt = ZonedDateTime.now();

    // Getters and Setters

    public Long getToken() { return token; }
    public void setToken(Long token) { this.token = token; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getExchange() { return exchange; }
    public void setExchange(String exchange) { this.exchange = exchange; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getIndustry() { return industry; }
    public void setIndustry(String industry) { this.industry = industry; }

    public Double getFaceValue() { return faceValue; }
    public void setFaceValue(Double faceValue) { this.faceValue = faceValue; }

    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }

    public ZonedDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(ZonedDateTime updatedAt) { this.updatedAt = updatedAt; }
}
