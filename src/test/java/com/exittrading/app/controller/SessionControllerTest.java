package com.exittrading.app.controller;

import com.exittrading.app.service.core.IstClock;
import com.exittrading.app.service.core.KiteSessionManager;
import com.exittrading.app.service.core.SettingsService;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionControllerTest {

    private KiteSessionManager sessionManager;
    private SessionController controller;
    private FixedClock clock;
    private SettingsService settingsService;

    @BeforeEach
    void setUp() {
        clock = new FixedClock(ZonedDateTime.of(2024, 5, 1, 9, 0, 0, 0, ZoneId.of("Asia/Kolkata")));
        sessionManager = new KiteSessionManager(clock);
        settingsService = mock(SettingsService.class);
        when(settingsService.getString("kite.apiKey", null)).thenReturn("sampleKey");
        when(settingsService.getString("kite.apiSecret", null)).thenReturn("sampleSecret");
        controller = new SessionController(sessionManager, clock, null, null, null, settingsService);
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
