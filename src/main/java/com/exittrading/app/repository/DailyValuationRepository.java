package com.exittrading.app.repository;

import com.exittrading.app.domain.DailyValuation;
import com.exittrading.app.domain.ReportKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DailyValuationRepository extends JpaRepository<DailyValuation, ReportKey> {
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "INSERT INTO daily_valuations (report_date, symbol, pe_ratio, book_value, dividend_yield, roce, roe, debt_to_equity, piotroski_score, qtr_sales_var, qtr_profit_var) " +
            "VALUES (:#{#d.id.reportDate}, :#{#d.id.symbol}, :#{#d.peRatio}, :#{#d.bookValue}, :#{#d.dividendYield}, :#{#d.roce}, :#{#d.roe}, :#{#d.debtToEquity}, :#{#d.piotroskiScore}, :#{#d.qtrSalesVar}, :#{#d.qtrProfitVar}) " +
            "ON CONFLICT (report_date, symbol) DO UPDATE SET " +
            "pe_ratio = EXCLUDED.pe_ratio, book_value = EXCLUDED.book_value, dividend_yield = EXCLUDED.dividend_yield, roce = EXCLUDED.roce, " +
            "roe = EXCLUDED.roe, debt_to_equity = EXCLUDED.debt_to_equity, piotroski_score = EXCLUDED.piotroski_score, qtr_sales_var = EXCLUDED.qtr_sales_var, qtr_profit_var = EXCLUDED.qtr_profit_var", nativeQuery = true)
    void upsert(@org.springframework.data.repository.query.Param("d") DailyValuation d);
}
