package com.exittrading.app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight metadata cache for instrument tick size and circuit limits
 * fetched from Kite Connect's instruments master. Designed to be safe when
 * the Kite jar is absent: lookups will simply return null.
 */
@Service
public class InstrumentService {

    private static final Logger log = LoggerFactory.getLogger(InstrumentService.class);

    public static class InstrumentMeta {
        public String exchange;
        public String symbol;
        public String token;
        public Double tickSize;
        public Double lowerCircuit;
        public Double upperCircuit;
    }

    private final KiteSessionManager sessionManager;

    private final Map<String, InstrumentMeta> byToken = new ConcurrentHashMap<>();
    private final Map<String, InstrumentMeta> byExchangeSymbol = new ConcurrentHashMap<>();
    private volatile boolean loaded = false;

    public InstrumentService(KiteSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    public InstrumentMeta findByToken(String token) {
        if (token == null) return null;
        ensureLoaded();
        return byToken.get(token);
    }

    public InstrumentMeta find(String exchange, String symbol, String token) {
        ensureLoaded();
        if (token != null) {
            InstrumentMeta meta = byToken.get(token);
            if (meta != null) return meta;
        }
        if (exchange != null && symbol != null) {
            return byExchangeSymbol.get(key(exchange, symbol));
        }
        return null;
    }

    private void ensureLoaded() {
        if (loaded) return;
        synchronized (this) {
            if (loaded) return;
            try {
                Object kite = sessionManager.getKiteConnect();
                if (kite == null) return;
                Method getInstruments = kite.getClass().getMethod("getInstruments", String.class);
                for (String ex : new String[]{"NSE", "BSE"}) {
                    Object res = getInstruments.invoke(kite, ex);
                    if (!(res instanceof List<?> list)) continue;
                    for (Object inst : list) {
                        InstrumentMeta meta = toMeta(inst);
                        if (meta == null) continue;
                        byToken.put(meta.token, meta);
                        byExchangeSymbol.put(key(meta.exchange, meta.symbol), meta);
                    }
                }
                loaded = true;
                log.info("Instrument metadata loaded: {} entries", byToken.size());
            } catch (Exception e) {
                log.warn("Instrument metadata load failed: {}", e.getMessage());
            }
        }
    }

    private static InstrumentMeta toMeta(Object inst) {
        try {
            Class<?> c = inst.getClass();
            Object exchange = getField(c, inst, "exchange");
            Object sym = getField(c, inst, "tradingsymbol");
            if (sym == null) sym = getField(c, inst, "tradingSymbol");
            Object token = getField(c, inst, "instrument_token");
            if (token == null) token = getField(c, inst, "instrumentToken");
            Object tick = getField(c, inst, "tick_size");
            if (tick == null) tick = getField(c, inst, "tickSize");
            Object lcl = getField(c, inst, "lower_circuit_limit");
            if (lcl == null) lcl = getField(c, inst, "lowerCircuitLimit");
            Object ucl = getField(c, inst, "upper_circuit_limit");
            if (ucl == null) ucl = getField(c, inst, "upperCircuitLimit");
            if (exchange == null || sym == null || token == null) return null;
            InstrumentMeta m = new InstrumentMeta();
            m.exchange = String.valueOf(exchange);
            m.symbol = String.valueOf(sym);
            m.token = String.valueOf(token);
            m.tickSize = asDouble(tick);
            m.lowerCircuit = asDouble(lcl);
            m.upperCircuit = asDouble(ucl);
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    private static String key(String exchange, String symbol) {
        return exchange.toUpperCase() + ":" + symbol.toUpperCase();
    }

    private static Object getField(Class<?> type, Object target, String fieldName) {
        try {
            java.lang.reflect.Field f = type.getField(fieldName);
            f.setAccessible(true);
            return f.get(target);
        } catch (Exception ignoredPublic) {
            try {
                java.lang.reflect.Field df = type.getDeclaredField(fieldName);
                df.setAccessible(true);
                return df.get(target);
            } catch (Exception ignoredPrivate) {
                return null;
            }
        }
    }

    private static Double asDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o == null) return null;
        try { return new BigDecimal(String.valueOf(o)).doubleValue(); } catch (Exception e) { return null; }
    }
}
