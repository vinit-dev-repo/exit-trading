package com.exittrading.app.repository;

import com.exittrading.app.domain.ShareholdingPattern;
import com.exittrading.app.domain.ReportKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ShareholdingPatternRepository extends JpaRepository<ShareholdingPattern, ReportKey> {
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "INSERT INTO shareholding_patterns (report_date, symbol, promoter_pct, public_pct, pledged_pct, change_prom_hold) " +
            "VALUES (:#{#s.id.reportDate}, :#{#s.id.symbol}, :#{#s.promoterPct}, :#{#s.publicPct}, :#{#s.pledgedPct}, :#{#s.changePromHold}) " +
            "ON CONFLICT (report_date, symbol) DO UPDATE SET " +
            "promoter_pct = EXCLUDED.promoter_pct, public_pct = EXCLUDED.public_pct, pledged_pct = EXCLUDED.pledged_pct, change_prom_hold = EXCLUDED.change_prom_hold", nativeQuery = true)
    void upsert(@org.springframework.data.repository.query.Param("s") ShareholdingPattern s);
}
