package com.exittrading.app.repository;

import com.exittrading.app.domain.KiteSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface KiteSessionRepository extends JpaRepository<KiteSession, String> {
    Optional<KiteSession> findByIsActiveTrue();
    Optional<KiteSession> findTopByIsActiveTrueOrderByUpdatedAtDesc();

    @org.springframework.data.jpa.repository.Query("SELECT ks FROM KiteSession ks LEFT JOIN FETCH ks.user WHERE ks.isActive = true ORDER BY ks.updatedAt DESC")
    java.util.List<KiteSession> findActiveSessionsWithUser();

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "INSERT INTO kite_sessions (session_id, user_id, api_key, access_token, public_token, login_time, expires_at, is_active, updated_at) " +
            "VALUES (:#{#k.sessionId}, :#{#k.user.id}, :#{#k.apiKey}, :#{#k.accessToken}, :#{#k.publicToken}, :#{#k.loginTime}, :#{#k.expiresAt}, :#{#k.isActive}, NOW()) " +
            "ON CONFLICT (session_id) DO UPDATE SET " +
            "user_id = EXCLUDED.user_id, api_key = EXCLUDED.api_key, access_token = EXCLUDED.access_token, public_token = EXCLUDED.public_token, " +
            "login_time = EXCLUDED.login_time, expires_at = EXCLUDED.expires_at, is_active = EXCLUDED.is_active, updated_at = NOW()", nativeQuery = true)
    void upsert(@org.springframework.data.repository.query.Param("k") KiteSession k);
}
