package com.exittrading.app.service;

import com.exittrading.app.domain.OrderSide;
import com.exittrading.app.domain.TradingSchedule;
import com.exittrading.app.dto.DepthView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
@Primary
@ConditionalOnClass(name = "com.zerodhatech.kiteconnect.KiteConnect")
public class DefaultKiteGateway implements KiteGateway {

    private static final Logger log = LoggerFactory.getLogger(DefaultKiteGateway.class);

    private final KiteSessionManager sessionManager;
    private final IstClock clock;

    @Value("${kite.default.exchange:NSE}")
    private String exchange;

    public DefaultKiteGateway(KiteSessionManager sessionManager, IstClock clock) {
        this.sessionManager = sessionManager;
        this.clock = clock;
    }

    @Override
    public CompletableFuture<String> placePcaOrder(TradingSchedule schedule) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Object kite = sessionManager.getKiteConnect();
                Class<?> orderParamsClass = Class.forName("com.zerodhatech.models.OrderParams");
                Object orderParams = orderParamsClass.getConstructor().newInstance();

                // Required fields
                setField(orderParamsClass, orderParams, "tradingsymbol", schedule.getTradingsymbol());
                setField(orderParamsClass, orderParams, "quantity", schedule.getQuantity());

                // Transaction and order type
                Object txnBuy = safeStaticField(orderParamsClass, "TRANSACTION_TYPE_BUY", "BUY");
                Object txnSell = safeStaticField(orderParamsClass, "TRANSACTION_TYPE_SELL", "SELL");
                Object ordLimit = safeStaticField(orderParamsClass, "ORDER_TYPE_LIMIT", "LIMIT");
                Object ordMarket = safeStaticField(orderParamsClass, "ORDER_TYPE_MARKET", "MARKET");
                setField(orderParamsClass, orderParams, "transactionType",
                        schedule.getSide() == OrderSide.BUY ? txnBuy : txnSell);
                setField(orderParamsClass, orderParams, "orderType",
                        schedule.getLimitPrice() != null ? ordLimit : ordMarket);

                // Misc
                setField(orderParamsClass, orderParams, "exchange", exchange);
                setField(orderParamsClass, orderParams, "product",
                        safeStaticField(orderParamsClass, "PRODUCT_MIS", "MIS"));
                setField(orderParamsClass, orderParams, "validity",
                        safeStaticField(orderParamsClass, "VALIDITY_DAY", "DAY"));
                if (schedule.getLimitPrice() != null) {
                    setField(orderParamsClass, orderParams, "price", schedule.getLimitPrice().doubleValue());
                }
                setField(orderParamsClass, orderParams, "tag", "PCA");

                // placeOrder(orderParams, variety)
                Method placeOrder = kite.getClass().getMethod("placeOrder", orderParamsClass, String.class);
                Object varietyConst = safeStaticField(Class.forName("com.zerodhatech.kiteconnect.KiteConnect"),
                        "VARIETY_REGULAR", "regular");
                Object response = placeOrder.invoke(kite, orderParams, String.valueOf(varietyConst));

                if (response instanceof Map<?, ?> map) {
                    Object orderId = map.get("order_id");
                    return orderId != null ? orderId.toString() : "UNKNOWN";
                }
                try {
                    Class<?> orderClass = Class.forName("com.zerodhatech.models.Order");
                    if (orderClass.isInstance(response)) {
                        Object orderId = getField(orderClass, response, "orderId");
                        return orderId != null ? orderId.toString() : "UNKNOWN";
                    }
                } catch (ClassNotFoundException ignored) {
                    // Fall through
                }
                return "UNKNOWN";
            } catch (Exception ex) {
                log.error("Order placement failed", ex);
                throw new RuntimeException("Order placement failed: " + ex.getMessage(), ex);
            }
        }, sessionManager.getExecutionPool());
    }

    @Override
    public CompletableFuture<Void> cancelOpenOrders(String tradingsymbol, OrderSide side) {
        return CompletableFuture.runAsync(() -> {
            try {
                Object kite = sessionManager.getKiteConnect();
                Method getOrders = kite.getClass().getMethod("getOrders");
                Object result = getOrders.invoke(kite);
                List<?> orders = result instanceof List<?> list ? list : Collections.emptyList();
                for (Object order : orders) {
                    Class<?> orderClass = order.getClass();
                    String symbol = String.valueOf(getField(orderClass, order, "tradingsymbol"));
                    String status = String.valueOf(getField(orderClass, order, "status"));
                    if (!tradingsymbol.equalsIgnoreCase(symbol) || !"OPEN".equalsIgnoreCase(status)) {
                        continue;
                    }
                    if (side != null) {
                        String transactionType = String.valueOf(getField(orderClass, order, "transactionType"));
                        if (side == OrderSide.BUY && !transactionType.toUpperCase().contains("BUY")) {
                            continue;
                        }
                        if (side == OrderSide.SELL && !transactionType.toUpperCase().contains("SELL")) {
                            continue;
                        }
                    }
                    String orderId = String.valueOf(getField(orderClass, order, "orderId"));
                    String variety = String.valueOf(getField(orderClass, order, "variety"));
                    Method cancelOrder = kite.getClass().getMethod("cancelOrder", String.class, String.class);
                    cancelOrder.invoke(kite, orderId, variety);
                    log.info("Cancelled open order {} for {}", orderId, tradingsymbol);
                }
            } catch (Exception ex) {
                log.warn("Failed cancelling open orders for {}: {}", tradingsymbol, ex.getMessage());
            }
        }, sessionManager.getExecutionPool());
    }

    @Override
    public CompletableFuture<DepthView> fetchDepth(String tradingsymbol, String instrumentToken) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Object kite = sessionManager.getKiteConnect();
                String instrument = exchange + ":" + tradingsymbol;
                Method getQuote = kite.getClass().getMethod("getQuote", String[].class);
                Object depthResult = getQuote.invoke(kite, (Object) new String[]{instrument});
                DepthView view = new DepthView();
                view.setTradingsymbol(tradingsymbol);
                if (depthResult instanceof Map<?, ?> depthMap) {
                    Object quote = depthMap.get(instrument);
                    if (quote != null) {
                        Class<?> quoteClass = quote.getClass();
                        Object depth = getField(quoteClass, quote, "depth");
                        if (depth != null) {
                            Class<?> depthClass = depth.getClass();
                            Object buyLevels = getField(depthClass, depth, "buy");
                            Object sellLevels = getField(depthClass, depth, "sell");
                            long buyQty = aggregateDepth(buyLevels);
                            long sellQty = aggregateDepth(sellLevels);
                            view.setBuyQuantity(buyQty);
                            view.setSellQuantity(sellQty);
                        }
                        Number lastPrice = (Number) getField(quoteClass, quote, "lastPrice");
                        if (lastPrice == null) {
                            lastPrice = (Number) getField(quoteClass, quote, "lastTradedPrice");
                        }
                        if (lastPrice != null) {
                            view.setLtp(BigDecimal.valueOf(lastPrice.doubleValue()));
                        }
                    }
                }
                view.setCapturedAt(clock.now());
                return view;
            } catch (Exception ex) {
                log.warn("Depth fetch failed for {}: {}", tradingsymbol, ex.getMessage());
                DepthView fallback = new DepthView();
                fallback.setTradingsymbol(tradingsymbol);
                fallback.setCapturedAt(clock.now());
                return fallback;
            }
        }, sessionManager.getMarketDataPool());
    }

    private long aggregateDepth(Object levels) {
        if (!(levels instanceof List<?> list)) {
            return 0;
        }
        long total = 0;
        for (Object item : list) {
            Object quantity = getField(item.getClass(), item, "quantity");
            if (quantity instanceof Number number) {
                total += number.longValue();
            }
        }
        return total;
    }

    private void setField(Class<?> type, Object target, String fieldName, Object value) throws Exception {
        Field field = type.getField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Object getField(Class<?> type, Object target, String fieldName) {
        try {
            Field field = type.getField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Object getStaticField(Class<?> type, String name) throws Exception {
        Field field = type.getField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private Object safeStaticField(Class<?> type, String name, Object fallback) {
        try {
            return getStaticField(type, name);
        } catch (Exception e) {
            return fallback;
        }
    }
}
