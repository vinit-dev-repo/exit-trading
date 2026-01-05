package com.exittrading.app.dto;

import java.util.Map;

public record SettingsUpdateRequest(Map<String, Object> updates) {
}
