package com.exittrading.app.repository;

import com.exittrading.app.domain.DepthSnapshot;
import com.exittrading.app.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DepthSnapshotRepository extends JpaRepository<DepthSnapshot, Long> {
    List<DepthSnapshot> findTop10ByUserOrderByCapturedAtDesc(UserAccount user);
    List<DepthSnapshot> findTop1ByUserAndTradingsymbolOrderByCapturedAtDesc(UserAccount user, String tradingsymbol);
}
