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

                setField(orderParamsClass, orderParams, "tradingsymbol", schedule.getTradingsymbol());
                setField(orderParamsClass, orderParams, "quantity", schedule.getQuantity());
                setField(orderParamsClass, orderParams, "transactionType",
                        schedule.getSide() == OrderSide.BUY ? getStaticField(orderParamsClass, "TRANSACTION_TYPE_BUY")
                                : getStaticField(orderParamsClass, "TRANSACTION_TYPE_SELL"));
                setField(orderParamsClass, orderParams, "orderType",
                        schedule.getLimitPrice() != null ? getStaticField(orderParamsClass, "ORDER_TYPE_LIMIT")
                                : getStaticField(orderParamsClass, "ORDER_TYPE_MARKET"));
                setField(orderParamsClass, orderParams, "quantityType", getStaticField(orderParamsClass, "QUANTITY"));
                setField(orderParamsClass, orderParams, "exchange", exchange);
                setField(orderParamsClass, orderParams, "product", getStaticField(orderParamsClass, "PRODUCT_MIS"));
                setField(orderParamsClass, orderParams, "validity", getStaticField(orderParamsClass, "VALIDITY_DAY"));
                if (schedule.getLimitPrice() != null) {
                    setField(orderParamsClass, orderParams, "price", schedule.getLimitPrice().doubleValue());
                }
                setField(orderParamsClass, orderParams, "tag", "PCA");

                Method placeOrder = kite.getClass().getMethod("placeOrder", orderParamsClass, String.class);
                Object variety = getStaticField(Class.forName("com.zerodhatech.kiteconnect.KiteConnect"), "VARIETY_REGULAR");
                Object response = placeOrder.invoke(kite, orderParams, variety.toString());
                if (response instanceof Map<?, ?> map) {
                    Object orderId = map.get("order_id");
                    return orderId != null ? orderId.toString() : "UNKNOWN";
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
                Method getDepth = kite.getClass().getMethod("getDepth", String.class, String.class, int.class);
                Object depthResult = getDepth.invoke(kite, exchange, tradingsymbol, 1);
                DepthView view = new DepthView();
                view.setTradingsymbol(tradingsymbol);
                if (depthResult instanceof Map<?, ?> depthMap) {
                    Object depthData = depthMap.get(tradingsymbol);
                    if (depthData != null) {
                        Class<?> depthClass = depthData.getClass();
                        List<?> buys = (List<?>) getField(depthClass, depthData, "buy");
                        List<?> sells = (List<?>) getField(depthClass, depthData, "sell");
                        long buyQty = buys == null ? 0 : buys.stream().mapToLong(item -> ((Number) getField(item.getClass(), item, "quantity")).longValue()).sum();
                        long sellQty = sells == null ? 0 : sells.stream().mapToLong(item -> ((Number) getField(item.getClass(), item, "quantity")).longValue()).sum();
                        view.setBuyQuantity(buyQty);
                        view.setSellQuantity(sellQty);
                    }
                }
                Method getLtp = kite.getClass().getMethod("getLTP", String.class, String.class);
                Object ltpResult = getLtp.invoke(kite, exchange, tradingsymbol);
                if (ltpResult instanceof Map<?, ?> ltpMap) {
                    Object quote = ltpMap.get(tradingsymbol);
                    if (quote != null) {
                        Number lastPrice = (Number) getField(quote.getClass(), quote, "lastPrice");
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
}
