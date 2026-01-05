package com.exittrading.app.service.core;

import com.exittrading.app.domain.OrderSide;
import com.exittrading.app.domain.TradingSchedule;
import com.exittrading.app.dto.DepthView;

import java.util.concurrent.CompletableFuture;

/**
 * Interface for trading and market data operations.
 * Implementations handle interaction with the broker API (e.g. Zerodha Kite).
 */
public interface KiteGateway {

    CompletableFuture<String> placePcaOrder(TradingSchedule schedule);

    CompletableFuture<Void> cancelOpenOrders(String tradingsymbol, OrderSide side);

    CompletableFuture<DepthView> fetchDepth(String tradingsymbol, String instrumentToken);

    default CompletableFuture<DepthView> fetchDepth(TradingSchedule schedule) {
        return fetchDepth(schedule.getTradingsymbol(), schedule.getInstrumentToken());
    }

    CompletableFuture<com.zerodhatech.models.Order> getOrder(String orderId);

    CompletableFuture<Boolean> cancelOrder(String orderId);
}
