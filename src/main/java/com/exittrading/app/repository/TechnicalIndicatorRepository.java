package com.exittrading.app.repository;

import com.exittrading.app.domain.TechnicalIndicator;
import com.exittrading.app.domain.ReportKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TechnicalIndicatorRepository extends JpaRepository<TechnicalIndicator, ReportKey> {
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "INSERT INTO technical_indicators (report_date, symbol, dma_50, dma_200, avg_vol_1wk, avg_vol_1mth, return_1d, return_1wk) " +
            "VALUES (:#{#t.id.reportDate}, :#{#t.id.symbol}, :#{#t.dma50}, :#{#t.dma200}, :#{#t.avgVol1wk}, :#{#t.avgVol1mth}, :#{#t.return1d}, :#{#t.return1wk}) " +
            "ON CONFLICT (report_date, symbol) DO UPDATE SET " +
            "dma_50 = EXCLUDED.dma_50, dma_200 = EXCLUDED.dma_200, avg_vol_1wk = EXCLUDED.avg_vol_1wk, avg_vol_1mth = EXCLUDED.avg_vol_1mth, return_1d = EXCLUDED.return_1d, return_1wk = EXCLUDED.return_1wk", nativeQuery = true)
    void upsert(@org.springframework.data.repository.query.Param("t") TechnicalIndicator t);
}
