package com.exittrading.app.service;

import com.exittrading.app.config.TimezoneConfig;
import org.springframework.stereotype.Component;

import java.time.*;

@Component
public class IstClock {

    public ZoneId zoneId() {
        return TimezoneConfig.IST_ZONE;
    }

    public ZonedDateTime now() {
        return ZonedDateTime.now(zoneId());
    }

    public LocalDate today() {
        return LocalDate.now(zoneId());
    }

    public LocalDateTime nowLocal() {
        return LocalDateTime.now(zoneId());
    }

    public Instant toInstant(LocalDate date, LocalTime time) {
        return ZonedDateTime.of(date, time, zoneId()).toInstant();
    }

    public ZonedDateTime fromInstant(Instant instant) {
        return instant.atZone(zoneId());
    }
}
