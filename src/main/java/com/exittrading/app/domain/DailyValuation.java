package com.exittrading.app.domain;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "daily_valuations")
@IdClass(ReportKey.class)
public class DailyValuation {

    @Id
    private LocalDate reportDate;

    @Id
    private String symbol;

    private Double peRatio;
    private Double bookValue;
    private Double dividendYield;
    private Double roce;
    private Double roe;
    private Double debtToEquity;
    private Double piotroskiScore;
    private Double qtrSalesVar;
    private Double qtrProfitVar;

    // Getters and Setters
    public LocalDate getReportDate() { return reportDate; }
    public void setReportDate(LocalDate reportDate) { this.reportDate = reportDate; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public Double getPeRatio() { return peRatio; }
    public void setPeRatio(Double peRatio) { this.peRatio = peRatio; }

    public Double getBookValue() { return bookValue; }
    public void setBookValue(Double bookValue) { this.bookValue = bookValue; }

    public Double getDividendYield() { return dividendYield; }
    public void setDividendYield(Double dividendYield) { this.dividendYield = dividendYield; }

    public Double getRoce() { return roce; }
    public void setRoce(Double roce) { this.roce = roce; }

    public Double getRoe() { return roe; }
    public void setRoe(Double roe) { this.roe = roe; }

    public Double getDebtToEquity() { return debtToEquity; }
    public void setDebtToEquity(Double debtToEquity) { this.debtToEquity = debtToEquity; }

    public Double getPiotroskiScore() { return piotroskiScore; }
    public void setPiotroskiScore(Double piotroskiScore) { this.piotroskiScore = piotroskiScore; }

    public Double getQtrSalesVar() { return qtrSalesVar; }
    public void setQtrSalesVar(Double qtrSalesVar) { this.qtrSalesVar = qtrSalesVar; }

    public Double getQtrProfitVar() { return qtrProfitVar; }
    public void setQtrProfitVar(Double qtrProfitVar) { this.qtrProfitVar = qtrProfitVar; }
}
