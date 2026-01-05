package com.exittrading.app.repository;

import com.exittrading.app.domain.ScreenerPreset;
import com.exittrading.app.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScreenerPresetRepository extends JpaRepository<ScreenerPreset, Long> {
    List<ScreenerPreset> findByUserOrderByNameAsc(UserAccount user);
    Optional<ScreenerPreset> findByIdAndUser(Long id, UserAccount user);
}
