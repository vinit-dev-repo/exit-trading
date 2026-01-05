package com.exittrading.app.domain;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

public class ReportKey implements Serializable {
    private LocalDate reportDate;
    private String symbol;

    public ReportKey() {}

    public ReportKey(LocalDate reportDate, String symbol) {
        this.reportDate = reportDate;
        this.symbol = symbol;
    }

    public LocalDate getReportDate() { return reportDate; }
    public void setReportDate(LocalDate reportDate) { this.reportDate = reportDate; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReportKey that = (ReportKey) o;
        return Objects.equals(reportDate, that.reportDate) && Objects.equals(symbol, that.symbol);
    }

    @Override
    public int hashCode() {
        return Objects.hash(reportDate, symbol);
    }
}
