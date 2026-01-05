package com.exittrading.app.repository;

import com.exittrading.app.domain.ExecutedTrade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ExecutedTradeRepository extends JpaRepository<ExecutedTrade, UUID> {
}
