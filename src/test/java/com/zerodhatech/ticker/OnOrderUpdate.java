package com.zerodhatech.ticker;

import com.zerodhatech.models.Order;

public interface OnOrderUpdate {
    void onOrderUpdate(Order order);
}
