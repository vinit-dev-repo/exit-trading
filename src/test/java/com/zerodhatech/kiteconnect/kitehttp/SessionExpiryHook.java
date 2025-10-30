package com.zerodhatech.kiteconnect.kitehttp;

@FunctionalInterface
public interface SessionExpiryHook {
    void sessionExpired();
}
