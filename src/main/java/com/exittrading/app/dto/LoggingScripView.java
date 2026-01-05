package com.exittrading.app.dto;

import java.time.ZonedDateTime;

/**
 * DTO for viewing a logged scrip's details and latest status.
 */
public record LoggingScripView(Long id,
                               String exchange,
                               String tradingsymbol,
                               String instrumentToken,
                               boolean active,
                               ZonedDateTime addedAt,
                               ZonedDateTime lastLoggedAt,
                               DepthView lastSnapshot,
                               Double t1Close,
                               Double t2Close) {
}
