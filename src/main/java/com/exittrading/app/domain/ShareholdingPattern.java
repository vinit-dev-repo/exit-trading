package com.exittrading.app.domain;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "shareholding_patterns")
@IdClass(ReportKey.class)
public class ShareholdingPattern {

    @Id
    private LocalDate reportDate;

    @Id
    private String symbol;

    private Double promoterPct;
    private Double publicPct;
    private Double pledgedPct;
    private Double changePromHold;

    public LocalDate getReportDate() { return reportDate; }
    public void setReportDate(LocalDate reportDate) { this.reportDate = reportDate; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public Double getPromoterPct() { return promoterPct; }
    public void setPromoterPct(Double promoterPct) { this.promoterPct = promoterPct; }

    public Double getPublicPct() { return publicPct; }
    public void setPublicPct(Double publicPct) { this.publicPct = publicPct; }

    public Double getPledgedPct() { return pledgedPct; }
    public void setPledgedPct(Double pledgedPct) { this.pledgedPct = pledgedPct; }

    public Double getChangePromHold() { return changePromHold; }
    public void setChangePromHold(Double changePromHold) { this.changePromHold = changePromHold; }
}
