package com.exittrading.app.dto;

/**
 * DTO for instrument search results.
 */
public record InstrumentSearchResult(String exchange,
                                     String symbol,
                                     String name,
                                     String token,
                                     Double tickSize,
                                     Double lowerCircuit,
                                     Double upperCircuit) {
}
