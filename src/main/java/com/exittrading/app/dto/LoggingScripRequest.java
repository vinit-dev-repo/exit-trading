package com.exittrading.app.dto;

/**
 * DTO for requesting to add a scrip to the logging service.
 */
public record LoggingScripRequest(String exchange,
                                  String tradingsymbol,
                                  String instrumentToken,
                                  Boolean active) {
}
