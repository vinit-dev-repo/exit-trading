package com.exittrading.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
@ConfigurationProperties(prefix = "pca")
public class SessionSlotConfig {
    /**
     * List of slot times in HH:mm (24h) format. Example: ["09:30","10:30",...]
     */
    private List<String> slots = new ArrayList<>();

    public List<String> getSlots() { return slots; }
    public void setSlots(List<String> slots) { this.slots = slots; }

    public List<LocalTime> orderedTimes() {
        return slots == null ? List.of() : slots.stream()
                .map(s -> LocalTime.parse(s))
                .collect(Collectors.toList());
    }
}

