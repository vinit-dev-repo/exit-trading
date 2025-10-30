package com.zerodhatech.ticker;

import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.Tick;

import java.util.ArrayList;

public class KiteTicker {
    public static final int modeFull = 1;
    public static final int modeQuote = 2;
    public static final int modeLTP = 3;

    private final String accessToken;
    private final String apiKey;

    private OnConnect onConnect;
    private OnDisconnect onDisconnect;
    private OnOrderUpdate onOrderUpdate;
    private OnError onError;
    private OnTicks onTicks;
    private boolean connected;

    public KiteTicker(String accessToken, String apiKey) {
        this.accessToken = accessToken;
        this.apiKey = apiKey;
    }

    public void setOnConnectedListener(OnConnect listener) {
        this.onConnect = listener;
    }

    public void setOnDisconnectedListener(OnDisconnect listener) {
        this.onDisconnect = listener;
    }

    public void setOnOrderUpdateListener(OnOrderUpdate listener) {
        this.onOrderUpdate = listener;
    }

    public void setOnErrorListener(OnError listener) {
        this.onError = listener;
    }

    public void setOnTickerArrivalListener(OnTicks listener) {
        this.onTicks = listener;
    }

    public void setTryReconnection(boolean tryReconnection) {
        // no-op in stub
    }

    public void setMaximumRetries(int retries) {
        // no-op in stub
    }

    public void setMaximumRetryInterval(int seconds) {
        // no-op in stub
    }

    public void subscribe(ArrayList<Long> tokens) {
        // no-op
    }

    public void unsubscribe(ArrayList<Long> tokens) {
        // no-op
    }

    public void setMode(ArrayList<Long> tokens, int mode) {
        // no-op
    }

    public void connect() {
        connected = true;
        if (onConnect != null) {
            onConnect.onConnected();
        }
        if (onTicks != null) {
            ArrayList<Tick> ticks = new ArrayList<>();
            Tick tick = new Tick();
            tick.setLastTradedPrice(500.0);
            tick.setOi(10000);
            tick.setOpenInterestDayHigh(11000);
            tick.setOpenInterestDayLow(9000);
            tick.setChange(2.5);
            tick.setTickTimestamp(System.currentTimeMillis());
            tick.setLastTradedTime(System.currentTimeMillis());
            tick.getMarketDepth().get("buy").add(new com.zerodhatech.models.DepthLevel(499.5, 50));
            ticks.add(tick);
            onTicks.onTicks(ticks);
        }
        if (onOrderUpdate != null) {
            Order order = new Order();
            order.orderId = "OID";
            onOrderUpdate.onOrderUpdate(order);
        }
    }

    public boolean isConnectionOpen() {
        return connected;
    }

    public void disconnect() {
        connected = false;
        if (onDisconnect != null) {
            onDisconnect.onDisconnected();
        }
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getApiKey() {
        return apiKey;
    }
}
