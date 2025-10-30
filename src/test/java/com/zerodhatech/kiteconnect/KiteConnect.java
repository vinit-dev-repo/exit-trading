package com.zerodhatech.kiteconnect;

import com.zerodhatech.kiteconnect.kitehttp.SessionExpiryHook;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.OrderParams;
import com.zerodhatech.models.Quote;
import com.zerodhatech.models.User;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class KiteConnect {
    private final String apiKey;
    private String accessToken;
    private String publicToken;
    private SessionExpiryHook sessionExpiryHook;

    public static final AtomicInteger orderSequence = new AtomicInteger(100);
    public static final List<Order> ORDERS = new ArrayList<>();
    public static final Map<String, Quote> QUOTES = new HashMap<>();
    public static final Map<String, Quote> LTPS = new HashMap<>();
    public static final List<String> CANCELLED = new ArrayList<>();
    public static OrderParams LAST_ORDER_PARAMS;
    public static String LAST_VARIETY;
    public static User MOCK_USER;

    public KiteConnect(String apiKey) {
        this.apiKey = apiKey;
    }

    public User generateSession(String requestToken, String apiSecret) {
        if (MOCK_USER == null) {
            User user = new User();
            user.userName = "stub-user";
            user.accessToken = "access";
            user.publicToken = "public";
            user.accessTokenExpiry = new java.util.Date(System.currentTimeMillis() + 3_600_000);
            return user;
        }
        return MOCK_USER;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public void setPublicToken(String publicToken) {
        this.publicToken = publicToken;
    }

    public void setSessionExpiryHook(SessionExpiryHook hook) {
        this.sessionExpiryHook = hook;
    }

    public List<Order> getOrders() {
        return ORDERS;
    }

    public Order cancelOrder(String orderId, String variety) {
        CANCELLED.add(orderId + ":" + variety);
        Order order = new Order();
        order.orderId = orderId;
        return order;
    }

    public Map<String, Quote> getQuote(String[] instruments) {
        return QUOTES;
    }

    public Map<String, Quote> getLTP(String[] instruments) {
        return LTPS;
    }

    public Map<String, String> placeOrder(OrderParams params, String variety) {
        LAST_ORDER_PARAMS = params;
        LAST_VARIETY = variety;
        String orderId = "OID" + orderSequence.incrementAndGet();
        Map<String, String> response = new HashMap<>();
        response.put("order_id", orderId);
        return response;
    }

    public SessionExpiryHook getSessionExpiryHook() {
        return sessionExpiryHook;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getPublicToken() {
        return publicToken;
    }

    public String getApiKey() {
        return apiKey;
    }
}
