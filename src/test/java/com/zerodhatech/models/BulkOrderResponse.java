package com.zerodhatech.models;

public class BulkOrderResponse {
    public String orderId;
    public BulkOrderError bulkOrderError;

    public static class BulkOrderError {
        public String code;
        public String message;
    }
}
