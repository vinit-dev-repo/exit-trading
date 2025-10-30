package com.exittrading.app.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneId;
import java.util.TimeZone;

@Configuration
public class TimezoneConfig {

    public static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    @PostConstruct
    public void init() {
        TimeZone.setDefault(TimeZone.getTimeZone(IST_ZONE));
    }
}
