package com.exittrading.app.repository;

import com.exittrading.app.domain.Instrument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface InstrumentRepository extends JpaRepository<Instrument, Long> {
    Instrument findBySymbol(String symbol);
    java.util.List<Instrument> findBySymbolIn(java.util.Collection<String> symbols);
    java.util.Optional<Instrument> findByExchangeAndSymbol(String exchange, String symbol);

    @Modifying
    @Query(value = "INSERT INTO instruments (token, symbol, exchange, name, industry, face_value, source_url, updated_at) " +
            "VALUES (:#{#i.token}, :#{#i.symbol}, :#{#i.exchange}, :#{#i.name}, :#{#i.industry}, :#{#i.faceValue}, :#{#i.sourceUrl}, NOW()) " +
            "ON CONFLICT (token) DO UPDATE SET " +
            "symbol = EXCLUDED.symbol, exchange = EXCLUDED.exchange, name = EXCLUDED.name, industry = EXCLUDED.industry, " +
            "face_value = EXCLUDED.face_value, source_url = EXCLUDED.source_url, updated_at = NOW()", nativeQuery = true)
    void upsert(@Param("i") Instrument i);
}
