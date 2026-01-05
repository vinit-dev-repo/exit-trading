package com.exittrading.app.dto;

public record SettingItem(
        String key,
        String group,
        String type,
        Object value,
        Object defaultValue,
        String description,
        boolean overridden
) {
}
