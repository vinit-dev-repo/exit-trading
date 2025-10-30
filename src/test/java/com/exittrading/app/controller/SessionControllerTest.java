package com.exittrading.app.controller;

import com.exittrading.app.service.IstClock;
import com.exittrading.app.service.KiteSessionManager;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionControllerTest {

    private KiteSessionManager sessionManager;
    private SessionController controller;
    private FixedClock clock;

    @BeforeEach
    void setUp() {
        clock = new FixedClock(ZonedDateTime.of(2024, 5, 1, 9, 0, 0, 0, ZoneId.of("Asia/Kolkata")));
        sessionManager = new KiteSessionManager(clock);
        controller = new SessionController(sessionManager, clock);
        setField(controller, "apiKey", "sampleKey");
        setField(controller, "apiSecret", "sampleSecret");
    }

    @Test
    void shouldCreateSessionUsingKiteConnectSamples() {
        User user = new User();
        user.userName = "kite-user";
        user.accessToken = "access-token";
        user.publicToken = "public-token";
        user.accessTokenExpiry = java.util.Date.from(clock.now().plusHours(2).toInstant());
        KiteConnect.MOCK_USER = user;

        ResponseEntity<?> response = controller.login(Map.of("requestToken", "dummy"));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(sessionManager.getUserName()).isEqualTo("kite-user");
        assertThat(sessionManager.getExpiry()).isEqualTo(user.accessTokenExpiry.toInstant().atZone(clock.zoneId()));

        KiteConnect kite = (KiteConnect) sessionManager.getKiteConnect();
        assertThat(kite.getApiKey()).isEqualTo("sampleKey");
        assertThat(kite.getAccessToken()).isEqualTo("access-token");
        assertThat(kite.getPublicToken()).isEqualTo("public-token");
        assertThat(kite.getSessionExpiryHook()).isNotNull();
    }

    private void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static class FixedClock extends IstClock {
        private final ZonedDateTime now;

        FixedClock(ZonedDateTime now) {
            this.now = now;
        }

        @Override
        public ZonedDateTime now() {
            return now;
        }

        @Override
        public ZonedDateTime fromInstant(Instant instant) {
            return instant.atZone(zoneId());
        }
    }
}
