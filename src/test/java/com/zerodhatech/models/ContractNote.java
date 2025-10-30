package com.zerodhatech.models;

public class ContractNote {
    public Charges charges = new Charges();

    public static class Charges {
        public double total;
    }
}
