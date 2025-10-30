package com.zerodhatech.kiteconnect.kitehttp.exceptions;

public class KiteException extends Exception {
    public String code;
    public String message;

    public KiteException(String message) {
        super(message);
        this.message = message;
    }

    public KiteException(String code, String message) {
        super(message);
        this.code = code;
        this.message = message;
    }
}
