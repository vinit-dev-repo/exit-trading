package com.zerodhatech.ticker;

import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;

public interface OnError {
    void onError(Exception exception);
    void onError(KiteException kiteException);
    void onError(String error);
}
