package com.exittrading.app.repository;

import com.exittrading.app.domain.EsmMonitorState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EsmMonitorStateRepository extends JpaRepository<EsmMonitorState, Long> {

    List<EsmMonitorState> findByActiveTrue();

    Optional<EsmMonitorState> findTopByScheduleIdAndActiveTrueOrderByUpdatedAtDesc(Long scheduleId);

    Optional<EsmMonitorState> findTopByOrderIdAndActiveTrueOrderByUpdatedAtDesc(String orderId);
}
