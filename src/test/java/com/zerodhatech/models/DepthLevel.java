package com.zerodhatech.models;

public class DepthLevel {
    public double price;
    public long quantity;

    public DepthLevel() {}

    public DepthLevel(double price, long quantity) {
        this.price = price;
        this.quantity = quantity;
    }

    public double getPrice() {
        return price;
    }

    public long getQuantity() {
        return quantity;
    }
}
