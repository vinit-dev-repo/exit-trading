package com.exittrading.app.domain;

import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

public enum SessionSlot {
    SLOT_0930(LocalTime.of(9, 30)),
    SLOT_1030(LocalTime.of(10, 30)),
    SLOT_1130(LocalTime.of(11, 30)),
    SLOT_1230(LocalTime.of(12, 30)),
    SLOT_1330(LocalTime.of(13, 30)),
    SLOT_1430(LocalTime.of(14, 30));

    private final LocalTime time;

    SessionSlot(LocalTime time) {
        this.time = time;
    }

    public LocalTime getTime() {
        return time;
    }

    public static List<SessionSlot> ordered() {
        return Arrays.asList(values());
    }
}
