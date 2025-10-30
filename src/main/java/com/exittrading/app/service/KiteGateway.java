package com.exittrading.app.service;

import com.exittrading.app.domain.OrderSide;
import com.exittrading.app.domain.TradingSchedule;
import com.exittrading.app.dto.DepthView;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface KiteGateway {

    CompletableFuture<String> placePcaOrder(TradingSchedule schedule);

    CompletableFuture<Void> cancelOpenOrders(String tradingsymbol, OrderSide side);

    CompletableFuture<DepthView> fetchDepth(String tradingsymbol, String instrumentToken);

    default CompletableFuture<DepthView> fetchDepth(TradingSchedule schedule) {
        return fetchDepth(schedule.getTradingsymbol(), schedule.getInstrumentToken());
    }
}
