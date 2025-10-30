package com.exittrading.app.service;

import com.exittrading.app.domain.OrderSide;
import com.exittrading.app.domain.TradingSchedule;
import com.exittrading.app.dto.DepthView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
@ConditionalOnMissingBean(KiteGateway.class)
public class PaperTradingGateway implements KiteGateway {

    private static final Logger log = LoggerFactory.getLogger(PaperTradingGateway.class);

    private final Map<String, String> lastOrderIdBySymbol = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private final IstClock clock;

    public PaperTradingGateway(IstClock clock) {
        this.clock = clock;
    }

    @Override
    public CompletableFuture<String> placePcaOrder(TradingSchedule schedule) {
        return CompletableFuture.supplyAsync(() -> {
            String orderId = "SIM" + System.nanoTime();
            lastOrderIdBySymbol.put(schedule.getTradingsymbol(), orderId);
            log.info("[PAPER] Simulated {} order {} for {} qty {}", schedule.getSide(), orderId, schedule.getTradingsymbol(), schedule.getQuantity());
            return orderId;
        });
    }

    @Override
    public CompletableFuture<Void> cancelOpenOrders(String tradingsymbol, OrderSide side) {
        return CompletableFuture.runAsync(() -> {
            if (lastOrderIdBySymbol.containsKey(tradingsymbol)) {
                log.info("[PAPER] Cancelled simulated orders for {}", tradingsymbol);
                lastOrderIdBySymbol.remove(tradingsymbol);
            }
        });
    }

    @Override
    public CompletableFuture<DepthView> fetchDepth(String tradingsymbol, String instrumentToken) {
        return CompletableFuture.supplyAsync(() -> {
            DepthView view = new DepthView();
            view.setTradingsymbol(tradingsymbol);
            long buy = 1000 + random.nextInt(500);
            long sell = 1000 + random.nextInt(500);
            view.setBuyQuantity(buy);
            view.setSellQuantity(sell);
            view.setLtp(BigDecimal.valueOf(100 + random.nextDouble() * 50));
            view.setCapturedAt(clock.now());
            return view;
        });
    }
}
