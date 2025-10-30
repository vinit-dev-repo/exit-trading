package com.zerodhatech.models;

public class Margin {
    public Available available = new Available();
    public Utilised utilised = new Utilised();

    public static class Available {
        public double cash;
    }

    public static class Utilised {
        public double debits;
        public double m2mUnrealised;
    }
}
