package com.exittrading.app.domain;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "technical_indicators")
@IdClass(ReportKey.class)
public class TechnicalIndicator {

    @Id
    private LocalDate reportDate;

    @Id
    private String symbol;

    @Column(name = "dma_50")
    private Double dma50;

    @Column(name = "dma_200")
    private Double dma200;

    @Column(name = "avg_vol_1wk")
    private Long avgVol1wk;

    @Column(name = "avg_vol_1mth")
    private Long avgVol1mth;

    @Column(name = "return_1d")
    private Double return1d;

    @Column(name = "return_1wk")
    private Double return1wk;

    public LocalDate getReportDate() { return reportDate; }
    public void setReportDate(LocalDate reportDate) { this.reportDate = reportDate; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public Double getDma50() { return dma50; }
    public void setDma50(Double dma50) { this.dma50 = dma50; }

    public Double getDma200() { return dma200; }
    public void setDma200(Double dma200) { this.dma200 = dma200; }

    public Long getAvgVol1wk() { return avgVol1wk; }
    public void setAvgVol1wk(Long avgVol1wk) { this.avgVol1wk = avgVol1wk; }

    public Long getAvgVol1mth() { return avgVol1mth; }
    public void setAvgVol1mth(Long avgVol1mth) { this.avgVol1mth = avgVol1mth; }

    public Double getReturn1d() { return return1d; }
    public void setReturn1d(Double return1d) { this.return1d = return1d; }

    public Double getReturn1wk() { return return1wk; }
    public void setReturn1wk(Double return1wk) { this.return1wk = return1wk; }
}
