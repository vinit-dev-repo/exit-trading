package com.exittrading.app.repository;

import com.exittrading.app.domain.DailyQuote;
import com.exittrading.app.domain.ReportKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DailyQuoteRepository extends JpaRepository<DailyQuote, ReportKey> {

    java.util.List<DailyQuote> findTop2BySymbolOrderByReportDateDesc(String symbol);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "INSERT INTO daily_quotes (report_date, token, symbol, current_price, high_price, low_price, volume, market_cap) " +
            "VALUES (:#{#d.id.reportDate}, :#{#d.token}, :#{#d.id.symbol}, :#{#d.currentPrice}, :#{#d.highPrice}, :#{#d.lowPrice}, :#{#d.volume}, :#{#d.marketCap}) " +
            "ON CONFLICT (report_date, symbol) DO UPDATE SET " +
            "token = EXCLUDED.token, current_price = EXCLUDED.current_price, high_price = EXCLUDED.high_price, low_price = EXCLUDED.low_price, " +
            "volume = EXCLUDED.volume, market_cap = EXCLUDED.market_cap", nativeQuery = true)
    void upsert(@org.springframework.data.repository.query.Param("d") DailyQuote d);
}
