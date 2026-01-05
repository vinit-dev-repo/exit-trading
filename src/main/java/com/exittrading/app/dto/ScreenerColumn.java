package com.exittrading.app.dto;

public record ScreenerColumn(
        String key,
        String label,
        String type,
        String group,
        boolean sortable,
        boolean filterable
) {
}
