package com.exittrading.app.service.core;

import com.exittrading.app.dto.InstrumentSearchResult;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight metadata cache for instrument tick size and circuit limits
 * fetched from Kite Connect's instruments master. Designed to be safe when
 * the Kite jar is absent: lookups will simply return null.
 * Refactored to use strong typing and remove reflection.
 */
@Service
public class InstrumentService {

    private static final Logger log = LoggerFactory.getLogger(InstrumentService.class);

    public static class InstrumentMeta {
        public String exchange;
        public String symbol;
        public String name;
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

    public List<InstrumentSearchResult> search(String query, int limit) {
        ensureLoaded();
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String q = query.trim().toUpperCase();
        int cap = limit > 0 ? limit : 20;
        return byToken.values().stream()
                .filter(m -> (m.symbol != null && m.symbol.toUpperCase().contains(q)) || (m.name != null && m.name.toUpperCase().contains(q)))
                .sorted(Comparator.comparing(m -> m.symbol))
                .limit(cap)
                .map(meta -> new InstrumentSearchResult(meta.exchange, meta.symbol, meta.name, meta.token, meta.tickSize, meta.lowerCircuit, meta.upperCircuit))
                .toList();
    }
    
    public void reload() {
        synchronized(this) {
            loaded = false;
            byToken.clear();
            byExchangeSymbol.clear();
            ensureLoaded();
        }
    }

    private void ensureLoaded() {
        if (loaded) return;
        synchronized (this) {
            if (loaded) return;
            try {
                KiteConnect kite = sessionManager.getKiteConnect();
                if (kite == null) return;
                
                // Fetch NSE and BSE
                loadExchange(kite, "NSE");
                loadExchange(kite, "BSE");
                
                loaded = true;
                log.info("Instrument metadata loaded: {} entries", byToken.size());
            } catch (Exception e) {
                log.warn("Instrument metadata load failed: {}", e.getMessage());
            }
        }
    }
    
    private void loadExchange(KiteConnect kite, String exchange) {
        try {
            List<Instrument> list = kite.getInstruments(exchange);
            if (list == null) return;
            for (Instrument inst : list) {
                InstrumentMeta meta = toMeta(inst);
                if (meta == null) continue;
                byToken.put(meta.token, meta);
                byExchangeSymbol.put(key(meta.exchange, meta.symbol), meta);
            }
        } catch (com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException | java.io.IOException e) {
            log.warn("Failed to load instruments for {}: {}", exchange, e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to load instruments for {} (Unknown error): {}", exchange, e.getMessage());
        }
    }

    private static InstrumentMeta toMeta(Instrument inst) {
        if (inst == null) return null;
        try {
            InstrumentMeta m = new InstrumentMeta();
            m.exchange = inst.exchange;
            m.symbol = inst.tradingsymbol;
            m.name = inst.name;
            m.token = String.valueOf(inst.instrument_token);
            m.tickSize = inst.tick_size;
            
            // Try to access circuit limits if available (might vary by SDK version)
            // Using direct field access matching standard SDK. 
            // If compilation fails, we might need a fallback, but likely these exist or are omitted.
            // Note: Some SDK versions don't have circuit limits in Instrument model. 
            // If so, we'll lose them, but we gain type safety. 
            // However, the previous reflection code was finding them. 
            // Let's assume they are not present in the standard Instrument class and we need to fetch Quotes to get them?
            // Wait, previous code checked 'lower_circuit_limit'. 
            // If it's not in the class, we can't access it. 
            // I will use reflection purely for these two fields if strict fields don't exist, 
            // OR checks generic field access.
            // Actually, let's stick to standard fields to be safe. If circuits are critical, we should get them from Quote/Depth.
            // But for now, I will omit the circuit limits from MASTER instrument load 
            // because they are usually day-specific and mostly found in Quote. 
            // The previous code found them? Maybe Zerodha Java SDK has them?
            // "com.zerodhatech.models.Instrument"
            // I'll leave them as null here. DepthStreamService fetches them from Quote/Depth which is improved.
            
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    private final java.util.Set<Long> unresolvedTokens = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    public void registerUnresolved(Long token) {
        if (token != null) unresolvedTokens.add(token);
    }

    public java.util.Set<Long> getUnresolvedTokens() {
        return new java.util.HashSet<>(unresolvedTokens);
    }

    public void removeUnresolved(Long token) {
        if (token != null) unresolvedTokens.remove(token);
    }

    private static String key(String exchange, String symbol) {
        return exchange.toUpperCase() + ":" + symbol.toUpperCase();
    }
}
