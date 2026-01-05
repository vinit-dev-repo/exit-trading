package com.exittrading.app.service.scrip;

import com.exittrading.app.domain.LoggingScrip;
import com.exittrading.app.domain.UserAccount;
import com.exittrading.app.dto.DepthView;
import com.exittrading.app.dto.LoggingScripRequest;
import com.exittrading.app.dto.LoggingScripView;
import com.exittrading.app.logging.ScripLogFormatter;
import com.exittrading.app.repository.LoggingScripRepository;
import com.exittrading.app.service.core.AdminService;
import com.exittrading.app.service.core.DepthService;
import com.exittrading.app.service.core.DepthStreamService;
import com.exittrading.app.service.core.CoalescingPersistenceService;
import com.exittrading.app.service.util.DepthViewUtil;
import com.exittrading.app.service.util.UserAccountUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;


/**
 * Service to manage scrips that are being monitored and logged.
 * Handles the scheduled fetching of market depth and persisting it to files/database.
 * Refactored to unify persistence logic and optimize performance.
 */
@Service
public class LoggingScripService {

    private static final Logger log = LoggerFactory.getLogger(LoggingScripService.class);
    private static final Logger rollingLog = LoggerFactory.getLogger("ImpactLog");

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime START = LocalTime.of(9, 0);
    private static final LocalTime END = LocalTime.of(16, 0);

    private final LoggingScripRepository repository;
    private final com.exittrading.app.repository.MarketSnapshotRepository marketSnapshotRepository;
    private final AdminService adminService;
    private final DepthService depthService;
    private final com.exittrading.app.repository.InstrumentRepository instrumentRepository;
    private final DepthStreamService depthStreamService;
    private final com.exittrading.app.service.core.InstrumentService instrumentService;
    private final com.exittrading.app.repository.DailyQuoteRepository dailyQuoteRepository;
    private final com.exittrading.app.service.core.SettingsService settingsService;
    
    // In-memory cache of last 1-minute stats for UI
    private final Map<Long, DepthView> lastSnapshots = new ConcurrentHashMap<>();
    private final Map<String, Double> prev2CloseCache = new ConcurrentHashMap<>();

    public LoggingScripService(LoggingScripRepository repository,
                               com.exittrading.app.repository.MarketSnapshotRepository marketSnapshotRepository,
                               AdminService adminService,
                               DepthService depthService,
                               com.exittrading.app.repository.InstrumentRepository instrumentRepository,
                               DepthStreamService depthStreamService,
                               com.exittrading.app.service.core.InstrumentService instrumentService,
                               com.exittrading.app.repository.DailyQuoteRepository dailyQuoteRepository,
                               CoalescingPersistenceService coalescingPersistenceService,
                               com.exittrading.app.service.core.SettingsService settingsService) {
        this.repository = repository;
        this.marketSnapshotRepository = marketSnapshotRepository;
        this.adminService = adminService;
        this.depthService = depthService;
        this.instrumentRepository = instrumentRepository;
        this.depthStreamService = depthStreamService;
        this.instrumentService = instrumentService;
        this.dailyQuoteRepository = dailyQuoteRepository;
        this.coalescingPersistenceService = coalescingPersistenceService;
        this.settingsService = settingsService;
    }
    
    private final CoalescingPersistenceService coalescingPersistenceService;


    @Transactional
    public void syncHoldings(String username) {
        UserAccount user = requireUser(username);
        if (user.getHoldings() == null || user.getHoldings().isEmpty()) return;
        
        int addedCount = 0;
        
        // Optimize: Load existing for user to avoid N+1 exists checks
        Set<String> existingKeys = repository.findByUserOrderByAddedAtDesc(user).stream()
                .map(s -> key(s.getExchange(), s.getTradingsymbol()))
                .collect(Collectors.toSet());

        for (String h : user.getHoldings()) {
            try {
                // correct parsing
                String[] parts = h.split("\\|");
                String first = parts.length > 0 ? parts[0] : "";
                String exchange = "NSE";
                String symbol = null;
                
                if (first.contains(":")) {
                    String[] split = first.split(":");
                    exchange = split[0];
                    symbol = split[1];
                } else {
                    symbol = UserAccountUtil.parseSymbol(h);
                }
                
                Long tokenVal = UserAccountUtil.parseToken(h);
                
                // Fallback lookup
                if (tokenVal == null && symbol != null) {
                    try {
                        com.exittrading.app.service.core.InstrumentService.InstrumentMeta meta = instrumentService.find(exchange, symbol, null);
                        if (meta != null && meta.token != null) {
                            tokenVal = Long.parseLong(meta.token);
                        }
                    } catch(Exception e) {}
                }

                if (tokenVal == null || symbol == null) continue;

                if (!existingKeys.contains(key(exchange, symbol))) {
                    LoggingScrip entity = new LoggingScrip();
                    entity.setUser(user);
                    entity.setExchange(exchange);
                    entity.setTradingsymbol(symbol);
                    entity.setInstrumentToken(String.valueOf(tokenVal));
                    entity.setActive(true);
                    entity.setAddedAt(ZonedDateTime.now(IST));
                    repository.save(entity);
                    existingKeys.add(key(exchange, symbol));
                    addedCount++;
                }
            } catch (Exception e) {
                log.debug("Failed to parse holding {}: {}", h, e.getMessage());
            }
        }
        if (addedCount > 0) {
            log.info("Synced {} new holdings to logging scrips for user {}", addedCount, username);
        }
    }

    @Transactional
    public void syncInstruments(String username) {
        UserAccount user = requireUser(username);
        
        // 1. Repair existing scrips
        List<LoggingScrip> existing = repository.findByUserOrderByAddedAtDesc(user);
        int repaired = 0;
        // Optimization: Could batch this, but repair is rare.
        for (LoggingScrip s : existing) {
            try {
                Optional<com.exittrading.app.domain.Instrument> master = instrumentRepository.findByExchangeAndSymbol(s.getExchange(), s.getTradingsymbol());
                if (master.isPresent() && master.get().getToken() != null) {
                    String correctToken = String.valueOf(master.get().getToken());
                    if (!correctToken.equals(s.getInstrumentToken())) {
                        s.setInstrumentToken(correctToken);
                        repository.save(s);
                        repaired++;
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to repair logging scrip {}:{}: {}", s.getExchange(), s.getTradingsymbol(), e.getMessage());
            }
        }
        if (repaired > 0) log.info("Repaired {} logging scrip tokens for user {}", repaired, username);

        // 2. Add Missing from Master (Optimized N+1)
        Set<String> holdingTokens = UserAccountUtil.extractHoldingTokens(user).stream().map(String::valueOf).collect(Collectors.toSet());
        
        Set<String> existingKeys = new HashSet<>();
        existing.forEach(s -> existingKeys.add(key(s.getExchange(), s.getTradingsymbol())));

        List<com.exittrading.app.domain.Instrument> instruments = instrumentRepository.findAll();
        log.info("Found {} instruments in database to sync for user {}", instruments.size(), username);
        
        int added = 0;
        List<LoggingScrip> batch = new ArrayList<>();
        
        for (com.exittrading.app.domain.Instrument inst : instruments) {
            if (inst.getSymbol() == null) continue;
            
            // Skip if in holdings (already handled by syncHoldings)
            if (inst.getToken() != null && holdingTokens.contains(String.valueOf(inst.getToken()))) {
                continue;
            }

            // Skip if already monitored
            if (existingKeys.contains(key(inst.getExchange(), inst.getSymbol()))) {
                continue;
            }
            
            LoggingScrip scrip = new LoggingScrip();
            scrip.setUser(user);
            scrip.setExchange(inst.getExchange() != null ? inst.getExchange() : "NSE");
            scrip.setTradingsymbol(inst.getSymbol());
            scrip.setInstrumentToken(inst.getToken() != null ? String.valueOf(inst.getToken()) : null);
            scrip.setActive(true);
            scrip.setAddedAt(ZonedDateTime.now(IST));
            
            batch.add(scrip);
            existingKeys.add(key(inst.getExchange(), inst.getSymbol()));
            added++;
        }
        
        if (!batch.isEmpty()) {
            repository.saveAll(batch);
            try {
                List<Long> tokens = batch.stream()
                    .map(LoggingScrip::getInstrumentToken)
                    .filter(Objects::nonNull)
                    .map(Long::parseLong)
                    .collect(Collectors.toList());
                if (!tokens.isEmpty()) {
                    depthStreamService.subscribe(tokens);
                }
            } catch (Exception e) {
                log.warn("Failed to subscribe to new logging scrips", e);
            }
        }

        
        // Force subscribe to ALL active scrips for this user to ensure stream is up
        try {
            List<LoggingScrip> allActive = repository.findByUserOrderByAddedAtDesc(user).stream()
                    .filter(LoggingScrip::isActive)
                    .collect(Collectors.toList());
            List<Long> allTokens = allActive.stream()
                        .map(LoggingScrip::getInstrumentToken)
                        .filter(Objects::nonNull)
                        .map(Long::parseLong)
                        .collect(Collectors.toList());
            if (!allTokens.isEmpty()) {
                depthStreamService.subscribe(allTokens);
            }
        } catch(Exception e) {
            log.warn("Failed to resubscribe active scrips", e);
        }

        log.info("Synced {} new logging scrips for user {}", added, username);
    }
    
    private String key(String ex, String sym) {
        return (ex != null ? ex.toUpperCase() : "NSE") + ":" + (sym != null ? sym.toUpperCase() : "");
    }

    @Transactional
    public List<LoggingScripView> list(String username) {
        UserAccount user = requireUser(username);
        List<LoggingScrip> scrips = repository.findByUserOrderByAddedAtDesc(user);
        Map<String, DepthView> fallbackBySymbol = new HashMap<>();
        try {
            List<DepthView> fallback = depthService.latestOrLive(user);
            for (DepthView view : fallback) {
                if (view == null || view.getTradingsymbol() == null) continue;
                fallbackBySymbol.put(normalizeSymbol(view.getTradingsymbol()), view);
            }
        } catch (Exception e) {
            log.debug("Failed to load fallback depth for logging list: {}", e.getMessage());
        }
        
        List<LoggingScripView> views = new ArrayList<>();
        for (LoggingScrip s : scrips) {
            LoggingScrip v = verifyAndRepair(s);
            if (v != null) {
                DepthView snapshot = lastSnapshots.get(v.getId());
                DepthView fallback = fallbackBySymbol.get(normalizeSymbol(v.getTradingsymbol()));
                if (fallback != null && (snapshot == null || DepthViewUtil.needsEnrichment(snapshot))) {
                    if (snapshot == null) {
                        snapshot = fallback;
                    } else {
                        DepthViewUtil.mergeMissing(snapshot, fallback);
                    }
                    lastSnapshots.put(v.getId(), snapshot);
                }
                views.add(toView(v));
            }
        }
        return views;
    }

    private static String normalizeSymbol(String symbol) {
        if (symbol == null) return null;
        String trimmed = symbol.trim().toUpperCase();
        int idx = trimmed.indexOf(':');
        return idx > -1 ? trimmed.substring(idx + 1) : trimmed;
    }

    private LoggingScrip verifyAndRepair(LoggingScrip s) {
        try {
            com.exittrading.app.service.core.InstrumentService.InstrumentMeta meta = instrumentService.find(s.getExchange(), s.getTradingsymbol(), null);
            
            if (meta == null) {
                // Fuzzy logic omitted for brevity as it's cleaner to separate repair
                // If needed, can re-add, but keeping list() fast is better.
            }
            
            if (meta != null && meta.token != null) {
                String freshToken = meta.token;
                if (!freshToken.equals(s.getInstrumentToken())) {
                    s.setInstrumentToken(freshToken);
                    repository.save(s);
                    try { depthStreamService.subscribe(Collections.singletonList(Long.parseLong(freshToken))); }
                    catch (Exception e) {
                        log.debug("Subscribe failed for {}:{}: {}", s.getExchange(), s.getTradingsymbol(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Instrument verify failed for {}:{}: {}", s.getExchange(), s.getTradingsymbol(), e.getMessage());
        }
        return s;
    }

    @Transactional
    public LoggingScripView add(String username, LoggingScripRequest request) {
        UserAccount user = requireUser(username);
        // ... (Similar logic, keeping core check)
         if (request == null || request.tradingsymbol() == null || request.tradingsymbol().isBlank()) {
            throw new IllegalArgumentException("tradingsymbol is required");
        }
        String symbol = request.tradingsymbol().trim().toUpperCase();
        String exchange = (request.exchange() != null && !request.exchange().isBlank()) ? request.exchange().trim().toUpperCase() : "NSE";
        if (repository.existsByUserAndExchangeIgnoreCaseAndTradingsymbolIgnoreCase(user, exchange, symbol)) {
            throw new IllegalArgumentException("Already added: " + exchange + ":" + symbol);
        }
        
        String token = request.instrumentToken();
        // Resolve logic ...
        try {
            com.exittrading.app.service.core.InstrumentService.InstrumentMeta meta = instrumentService.find(exchange, symbol, null);
            if (meta != null && meta.token != null) token = meta.token;
        } catch(Exception e) {
            log.debug("Instrument lookup failed for {}:{}: {}", exchange, symbol, e.getMessage());
        }
        
        LoggingScrip entity = new LoggingScrip();
        entity.setUser(user);
        entity.setExchange(exchange);
        entity.setTradingsymbol(symbol);
        entity.setInstrumentToken(token);
        entity.setActive(request.active() == null || request.active());
        entity.setAddedAt(ZonedDateTime.now(IST));
        LoggingScrip saved = repository.save(entity);
        if (saved.isActive() && saved.getInstrumentToken() != null) {
            try { depthStreamService.subscribe(Collections.singletonList(Long.parseLong(saved.getInstrumentToken()))); }
            catch (Exception e) {
                log.debug("Subscribe failed for {}:{}: {}", exchange, symbol, e.getMessage());
            }
        }
        return toView(saved);
    }
    
    // remove, toggle, captureOne, captureForUser ... (standard delegates)
    @Transactional
    public void remove(String username, Long id) {
        LoggingScrip scrip = findOwned(username, id);
        repository.delete(scrip);
        lastSnapshots.remove(id);
    }

    @Transactional
    public LoggingScripView toggle(String username, Long id, boolean active) {
        LoggingScrip scrip = findOwned(username, id);
        scrip.setActive(active);
        LoggingScrip saved = repository.save(scrip);
        if (active && saved.getInstrumentToken() != null) {
            try { depthStreamService.subscribe(Collections.singletonList(Long.parseLong(saved.getInstrumentToken()))); }
            catch (Exception e) {
                log.debug("Subscribe failed for {}:{}: {}", saved.getExchange(), saved.getTradingsymbol(), e.getMessage());
            }
        }
        return toView(saved);
    }

    @Transactional
    public LoggingScripView captureOne(String username, Long id) {
        LoggingScrip scrip = findOwned(username, id);
        return captureAndLog(scrip, true);
    }

    @Transactional
    public List<LoggingScripView> captureForUser(String username) {
        UserAccount user = requireUser(username);
        List<LoggingScrip> list = repository.findByUserOrderByAddedAtDesc(user);
        List<LoggingScripView> views = new ArrayList<>();
        for (LoggingScrip s : list) {
            try {
                LoggingScripView v = captureAndLog(s, true);
                if (v != null) views.add(v);
            } catch (Exception ex) {
                log.warn("Manual capture failed for {}:{} - {}", s.getExchange(), s.getTradingsymbol(), ex.getMessage());
            }
        }
        return views;
    }

    private final java.util.concurrent.atomic.AtomicLong lastScheduledRunMs = new java.util.concurrent.atomic.AtomicLong(0L);

    @Scheduled(fixedDelay = 250, initialDelay = 15000)
    public void scheduledCapture() {
        long intervalMs = settingsService.getLong("app.config.market.polling-interval-ms", 1000);
        long now = System.currentTimeMillis();
        long last = lastScheduledRunMs.get();
        if (now - last < intervalMs) return;
        if (!lastScheduledRunMs.compareAndSet(last, now)) return;

        if (!withinWindow()) return;
        try {
            List<LoggingScrip> active = repository.findByActiveTrue();
            // Rate Limiting for REST Fallback: Max 3 requests per second/cycle to avoid 429s
            int restFetchBudget = 3; 
            
            // Rotating/Shuffling to give fairness if budget limited? 
            // For now, simple iteration. If stream is down, first 3 get data. 
            // Ideally we shuffle or cycle, but let's stick to simple first.
            Collections.shuffle(active); // Simple randomization ensures eventual coverage

            for (LoggingScrip s : active) {
                try { 
                    CaptureResult result = captureAndLog(s, false, restFetchBudget > 0);
                    if (result.didRestFetch) restFetchBudget--;
                } catch (Exception ex) {
                    log.debug("Scheduled capture failed for {}:{}: {}", s.getExchange(), s.getTradingsymbol(), ex.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("Scheduled capture cycle failed: {}", e.getMessage());
        }
    }

    private boolean withinWindow() {
        try {
            ZonedDateTime now = ZonedDateTime.now(IST);
            DayOfWeek dow = now.getDayOfWeek();
            if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
            LocalTime t = now.toLocalTime();
            return !t.isBefore(START) && !t.isAfter(END);
        } catch (Exception e) { return false; }
    }

    private LoggingScripView captureAndLog(LoggingScrip scrip, boolean allowOutsideWindow) {
        return captureAndLog(scrip, allowOutsideWindow, true).view;
    }

    private static class CaptureResult {
        LoggingScripView view;
        boolean didRestFetch;
        CaptureResult(LoggingScripView v, boolean f) {this.view = v; this.didRestFetch = f;}
    }

    private CaptureResult captureAndLog(LoggingScrip scrip, boolean allowOutsideWindow, boolean canUseRest) {
        // Returns Pair of (View, performedRest)
        scrip = verifyAndRepair(scrip);
        if (scrip == null) return new CaptureResult(null, false);
        
        if (!allowOutsideWindow && !withinWindow()) return new CaptureResult(toView(scrip), false);
        
        String instrument = scrip.getExchange() + ":" + scrip.getTradingsymbol();
        String token = scrip.getInstrumentToken();
        
        DepthView depth = null;
        if (token != null) depth = depthStreamService.getSnapshot(token);
        
        // Resilience: Check if stream is actually providing data
        boolean streamActive = false;
        if (token != null) {
            long last = coalescingPersistenceService.getLastPersistTime(token);
            if (System.currentTimeMillis() - last < 30000) { // 30s tolerance
                streamActive = true;
            }
        }

        boolean performedRest = false;
        // Fallback Logic
        if (depth == null || (!streamActive && !allowOutsideWindow)) {
             // If stream is dead/empty, try REST only if permitted
             if (canUseRest && (allowOutsideWindow || !streamActive)) {
                 try { 
                     depth = depthService.fetchOne(instrument, token); 
                     performedRest = true;
                 } catch(Exception e) {}
             }
        }
        
        if (depth == null) return new CaptureResult(toView(scrip), false);
        
        // Refreshed capture time logic
        depth.setCapturedAt(ZonedDateTime.now(IST));
        DepthView prevSnapshot = lastSnapshots.get(scrip.getId());
        if (prevSnapshot != null) {
            DepthViewUtil.mergeMissing(depth, prevSnapshot);
        }
        lastSnapshots.put(scrip.getId(), depth);
        
        if (!allowOutsideWindow && streamActive) {
             scrip.setLastLoggedAt(ZonedDateTime.now(IST));
             return new CaptureResult(toView(scrip), false);
        }

        // Write to proper storages
        if (depth != null) {
            writeLogLine(scrip, depth);
            depthService.persistDepth(depth, token, allowOutsideWindow); 
        }
        
        scrip.setLastLoggedAt(ZonedDateTime.now(IST));
        return new CaptureResult(toView(scrip), performedRest);
    }

    private void writeLogLine(LoggingScrip scrip, DepthView v) {
        try {
            String instrument = scrip.getExchange() + ":" + scrip.getTradingsymbol();
            String token = scrip.getInstrumentToken() != null ? scrip.getInstrumentToken() : "-";
            rollingLog.info(ScripLogFormatter.format(instrument, token, v));
        } catch (Exception e) {
            log.debug("Scrip log write failed for {}:{}: {}", scrip.getExchange(), scrip.getTradingsymbol(), e.getMessage());
        }
    }

    private LoggingScrip findOwned(String username, Long id) {
        UserAccount user = requireUser(username);
        return repository.findById(id)
                .filter(s -> Objects.equals(s.getUser().getId(), user.getId()))
                .orElseThrow(() -> new IllegalArgumentException("Unknown scrip id " + id));
    }

    private UserAccount requireUser(String username) {
        return adminService.findOptionalByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Unknown user " + username));
    }

    private Double getPrev2Close(String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        String key = symbol.trim().toUpperCase();
        Double cached = prev2CloseCache.get(key);
        if (cached != null) {
            return cached > 0 ? cached : null;
        }
        Double value = null;
        try {
            List<com.exittrading.app.domain.DailyQuote> quotes = dailyQuoteRepository.findTop2BySymbolOrderByReportDateDesc(key);
            if (quotes.size() > 1) value = quotes.get(1).getCurrentPrice();
        } catch (Exception e) {
            log.debug("Daily quote lookup failed for {}: {}", key, e.getMessage());
        }
        prev2CloseCache.put(key, value != null ? value : 0.0);
        return value;
    }

    private LoggingScripView toView(LoggingScrip scrip) {
        Double prev2 = getPrev2Close(scrip.getTradingsymbol());
        return new LoggingScripView(
                scrip.getId(),
                scrip.getExchange(),
                scrip.getTradingsymbol(),
                scrip.getInstrumentToken(),
                scrip.isActive(),
                scrip.getAddedAt(),
                scrip.getLastLoggedAt(),
                lastSnapshots.get(scrip.getId()),
                null,
                prev2
        );
    }
}
