package com.exittrading.app.repository;

import com.exittrading.app.domain.MarketSnapshot;
import com.exittrading.app.domain.SnapshotKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MarketSnapshotRepository extends JpaRepository<MarketSnapshot, SnapshotKey> {
    
    @Query(value = "select distinct on (token) * from market_snapshots " +
            "where token in (:tokens) order by token, captured_at desc", nativeQuery = true)
    java.util.List<MarketSnapshot> findLatestByTokenIn(@Param("tokens") java.util.Collection<Long> tokens);
}
