package com.exittrading.app.repository;

import com.exittrading.app.domain.LoggingScrip;
import com.exittrading.app.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoggingScripRepository extends JpaRepository<LoggingScrip, Long> {
    List<LoggingScrip> findByUserOrderByAddedAtDesc(UserAccount user);
    List<LoggingScrip> findByUserAndActiveTrue(UserAccount user);
    List<LoggingScrip> findByActiveTrue();
    boolean existsByUserAndExchangeIgnoreCaseAndTradingsymbolIgnoreCase(UserAccount user, String exchange, String tradingsymbol);
}
