package com.zerodhatech.ticker;

import com.zerodhatech.models.Tick;

import java.util.ArrayList;

public interface OnTicks {
    void onTicks(ArrayList<Tick> ticks);
}
