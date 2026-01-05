package com.exittrading.app.service.ingestion;

import com.exittrading.app.domain.*;
import com.exittrading.app.repository.*;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStreamReader;
import java.io.Reader;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@Service
public class CsvIngestionService {

    private static final Logger log = LoggerFactory.getLogger(CsvIngestionService.class);

    private final InstrumentRepository instrumentRepository;
    private final DailyQuoteRepository dailyQuoteRepository;
    private final DailyValuationRepository dailyValuationRepository;
    private final ShareholdingPatternRepository shareholdingPatternRepository;
    private final TechnicalIndicatorRepository technicalIndicatorRepository;
    private final com.exittrading.app.service.core.InstrumentService instrumentService;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    private final jakarta.persistence.EntityManager entityManager;
    private final com.exittrading.app.service.core.SettingsService settingsService;

    public CsvIngestionService(InstrumentRepository instrumentRepository,
                               DailyQuoteRepository dailyQuoteRepository,
                               DailyValuationRepository dailyValuationRepository,
                               ShareholdingPatternRepository shareholdingPatternRepository,
                               TechnicalIndicatorRepository technicalIndicatorRepository,
                               com.exittrading.app.service.core.InstrumentService instrumentService,
                               org.springframework.transaction.PlatformTransactionManager transactionManager,
                               jakarta.persistence.EntityManager entityManager,
                               com.exittrading.app.service.core.SettingsService settingsService) {
        this.instrumentRepository = instrumentRepository;
        this.dailyQuoteRepository = dailyQuoteRepository;
        this.dailyValuationRepository = dailyValuationRepository;
        this.shareholdingPatternRepository = shareholdingPatternRepository;
        this.technicalIndicatorRepository = technicalIndicatorRepository;
        this.instrumentService = instrumentService;
        this.transactionTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.entityManager = entityManager;
        this.settingsService = settingsService;
    }

    // Removed @Transactional to allow manual chunking
    public String ingestReport(MultipartFile file, LocalDate reportDate) {
        try (Reader reader = new InputStreamReader(file.getInputStream())) {
            return ingestReader(reader, reportDate);
        } catch (Exception e) {
            log.error("CSV Ingestion failed", e);
            throw new RuntimeException("Ingestion failed: " + e.getMessage());
        }
    }

    // Removed @Transactional
    public void ingestReport(java.nio.file.Path csvPath, LocalDate date) throws Exception {
        ingestReport(csvPath, date, java.util.Collections.emptyMap());
    }

    public void ingestReport(java.nio.file.Path csvPath, LocalDate date, java.util.Map<String, String> corrections) throws Exception {
        try (Reader reader = java.nio.file.Files.newBufferedReader(csvPath)) {
            ingestReader(reader, date, corrections);
        }
    }

    private String ingestReader(Reader reader, LocalDate reportDate) throws Exception {
        return ingestReader(reader, reportDate, java.util.Collections.emptyMap());
    }
    
    private String ingestReader(Reader reader, LocalDate reportDate, java.util.Map<String, String> corrections) throws Exception {
        try (CSVReader csvReader = new CSVReaderBuilder(reader).withSkipLines(0).build()) {
            int batchSize = settingsService.getInt("app.config.ingestion.batch-size", 200);

            String[] header = csvReader.readNext();
            if (header == null) return "Empty CSV file";

            Map<String, Integer> colMap = new HashMap<>();
            for (int i = 0; i < header.length; i++) {
                colMap.put(header[i].trim(), i);
            }

            int processed = 0;
            java.util.List<String[]> batch = new java.util.ArrayList<>(batchSize);
            java.util.List<String> errors = new java.util.ArrayList<>();
            String[] row;
            int rowNum = 0; // relative to data rows

            while ((row = csvReader.readNext()) != null) {
                rowNum++;
                batch.add(row);
                if (batch.size() >= batchSize) {
                    int saved = processBatch(batch, colMap, reportDate, errors, rowNum - batch.size() + 1, corrections);
                    processed += saved;
                    batch.clear();
                    entityManager.clear();
                }
            }
            if (!batch.isEmpty()) {
                int saved = processBatch(batch, colMap, reportDate, errors, rowNum - batch.size() + 1, corrections);
                processed += saved;
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("Processed ").append(processed).append(" rows.");
            if (!errors.isEmpty()) {
                sb.append(" Failures (or Batch Rollbacks): ").append(errors.size()).append("\n");
                for (String err : errors) {
                    if (sb.length() > 5000) { sb.append("... truncated"); break; }
                    sb.append(err).append("\n");
                }
            }
            return sb.toString();
        }
    }

    private int processBatch(java.util.List<String[]> batch, Map<String, Integer> colMap, LocalDate reportDate, java.util.List<String> errors, int startRow, java.util.Map<String, String> corrections) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
             java.util.List<Instrument> instruments = new java.util.ArrayList<>();
             java.util.List<DailyQuote> quotes = new java.util.ArrayList<>();
             java.util.List<DailyValuation> valuations = new java.util.ArrayList<>();
             java.util.List<ShareholdingPattern> patterns = new java.util.ArrayList<>();
             java.util.List<TechnicalIndicator> indicators = new java.util.ArrayList<>();

             // 0. Prefetch Instruments (N+1 Optimization)
             java.util.Set<String> symbols = new java.util.HashSet<>();
             int symIdx = colMap.getOrDefault("Symbol", -1);
             if (symIdx >= 0) {
                 for (String[] r : batch) {
                     if (r.length > symIdx && r[symIdx] != null) symbols.add(r[symIdx].trim());
                 }
             }
             Map<String, Instrument> instrumentMap = new java.util.HashMap<>();
             if (!symbols.isEmpty()) {
                 instrumentRepository.findBySymbolIn(symbols).forEach(i -> instrumentMap.put(i.getSymbol(), i));
             }

             // 1. Prepare Data
             for (int i = 0; i < batch.size(); i++) {
                 String[] row = batch.get(i);
                 try {
                     processRowData(row, colMap, reportDate, instruments, quotes, valuations, patterns, indicators, instrumentMap, corrections);
                 } catch (Exception e) {
                     errors.add("Row " + (startRow + i) + ": " + e.getMessage());
                 }
             }

             // 2. Batch Save (Idempotent Upsert via Merge in saveAll)
             try {
                if (!instruments.isEmpty()) instrumentRepository.saveAll(instruments);
                if (!quotes.isEmpty()) dailyQuoteRepository.saveAll(quotes);
                if (!valuations.isEmpty()) dailyValuationRepository.saveAll(valuations);
                if (!patterns.isEmpty()) shareholdingPatternRepository.saveAll(patterns);
                if (!indicators.isEmpty()) technicalIndicatorRepository.saveAll(indicators);
                return true; 
             } catch (Exception e) {
                 // If batch fails, we log it. Transaction rolls back for this chunk/batch.
                 log.error("Batch save failed: {}", e.getMessage());
                 errors.add("Batch " + startRow + "-" + (startRow+batch.size()-1) + " FAILED DB SAVE: " + e.getMessage());
                 status.setRollbackOnly();
                 return false;
             }
        })) ? batch.size() : 0;
    }

    private void processRowData(String[] row, Map<String, Integer> colMap, LocalDate reportDate,
                                java.util.List<Instrument> instruments,
                                java.util.List<DailyQuote> quotes,
                                java.util.List<DailyValuation> valuations,
                                java.util.List<ShareholdingPattern> patterns,
                                java.util.List<TechnicalIndicator> indicators,
                                Map<String, Instrument> instrumentCache,
                                java.util.Map<String, String> corrections) { // Added Cache Param & Corrections
        
        String originalSymbol = getStr(row, colMap, "Symbol");
        if (originalSymbol == null || originalSymbol.isBlank()) return;

        // Apply Correction
        if (corrections.containsKey(originalSymbol)) {
             String newSym = corrections.get(originalSymbol);
             // Strip prefix if present (e.g. NSE:TECHD-ST -> TECHD-ST)
             if (newSym != null && newSym.contains(":")) {
                 String[] parts = newSym.split(":");
                 if (parts.length > 1) {
                     String pExchange = parts[0].toUpperCase();
                     String pSymbol = parts[1];
                     
                     // Update Exchange column if exists
                     Integer exIdx = colMap.get("Exchange");
                     if (exIdx != null && exIdx < row.length) {
                         row[exIdx] = pExchange;
                     }
                     newSym = pSymbol;
                 }
             }

             // Update the row object in place to reflect correction for subsequent gets
             Integer idx = colMap.get("Symbol");
             if (idx != null && idx < row.length) {
                 row[idx] = newSym;
             }
        }
        
        String symbol = getStr(row, colMap, "Symbol"); // Re-read potentially updated symbol

        String exchange = getStr(row, colMap, "Exchange");
        Long token = parseLong(getStr(row, colMap, "Token"));

        // Instrument Update (Use Cache)
        Instrument inst = instrumentCache.get(symbol);
        if (inst == null) {
            inst = new Instrument();
            inst.setSymbol(symbol);
            
            // Fix: Try to resolve real token if missing
            if (token == null) {
                try {
                    com.exittrading.app.service.core.InstrumentService.InstrumentMeta meta = instrumentService.find(exchange, symbol, null);
                    if (meta != null && meta.token != null) token = Long.parseLong(meta.token);
                } catch (Exception e) {}
            }
            // Use 0L if unresolved, NOT nanoTime (which creates fake active tokens)
            inst.setToken(token != null ? token : 0L);
            
            // Update cache so subsequent rows with same symbol (unlikely in batch but possible) use same obj
            instrumentCache.put(symbol, inst);
        }
        inst.setExchange(exchange);
        if (token != null) inst.setToken(token);
        inst.setIndustry(getStr(row, colMap, "Industry"));
        inst.setFaceValue(parseDbl(getStr(row, colMap, "Face Value")));
        inst.setSourceUrl(getStr(row, colMap, "Source Url"));
        inst.setUpdatedAt(java.time.ZonedDateTime.now());
        instruments.add(inst);

        // Daily Quote
        DailyQuote q = new DailyQuote();
        q.setReportDate(reportDate);
        q.setSymbol(symbol);
        q.setToken(inst.getToken()); // Link to resolved token
        q.setCurrentPrice(parseDbl(getStr(row, colMap, "Current Price")));
        q.setHighPrice(parseDbl(getStr(row, colMap, "High")));
        q.setLowPrice(parseDbl(getStr(row, colMap, "Low")));
        q.setVolume(parseLong(getStr(row, colMap, "Volume")));
        q.setMarketCap(parseDbl(getStr(row, colMap, "Market Cap")));
        quotes.add(q);

        // Daily Valuation
        DailyValuation v = new DailyValuation();
        v.setReportDate(reportDate);
        v.setSymbol(symbol);
        v.setPeRatio(parseDbl(getStr(row, colMap, "Stock P/E")));
        v.setBookValue(parseDbl(getStr(row, colMap, "Book Value")));
        v.setDividendYield(parseDbl(getStr(row, colMap, "Dividend Yield")));
        v.setRoce(parseDbl(getStr(row, colMap, "ROCE")));
        v.setRoe(parseDbl(getStr(row, colMap, "ROE")));
        v.setDebtToEquity(parseDbl(getStr(row, colMap, "Debt to equity")));
        v.setPiotroskiScore(parseDbl(getStr(row, colMap, "Piotroski score")));
        v.setQtrSalesVar(parseDbl(getStr(row, colMap, "Qtr Sales Var")));
        v.setQtrProfitVar(parseDbl(getStr(row, colMap, "Qtr Profit Var")));
        valuations.add(v);

        // Shareholding
        ShareholdingPattern s = new ShareholdingPattern();
        s.setReportDate(reportDate);
        s.setSymbol(symbol);
        s.setPromoterPct(parseDbl(getStr(row, colMap, "Promoter %")));
        s.setPublicPct(parseDbl(getStr(row, colMap, "Public %")));
        s.setPledgedPct(parseDbl(getStr(row, colMap, "Pledged percentage")));
        s.setChangePromHold(parseDbl(getStr(row, colMap, "Change in Prom Hold")));
        patterns.add(s);

        // Technicals
        TechnicalIndicator t = new TechnicalIndicator();
        t.setReportDate(reportDate);
        t.setSymbol(symbol);
        t.setDma50(parseDbl(getStr(row, colMap, "DMA 50")));
        t.setDma200(parseDbl(getStr(row, colMap, "DMA 200")));
        t.setAvgVol1wk(parseLong(getStr(row, colMap, "Avg Vol 1Wk")));
        t.setAvgVol1mth(parseLong(getStr(row, colMap, "Avg Vol 1Mth")));
        t.setReturn1d(parseDbl(getStr(row, colMap, "Return over 1day")));
        t.setReturn1wk(parseDbl(getStr(row, colMap, "Return over 1week")));
        indicators.add(t);
    }

    private String getStr(String[] row, Map<String, Integer> colMap, String colName) {
        Integer idx = colMap.get(colName);
        if (idx == null || idx >= row.length) return null;
        return row[idx];
    }

    private Double parseDbl(String val) {
        if (val == null || val.isBlank()) return null;
        try {
            // Clean common junk non-numeric
            String clean = val.replace("%", "").replace(",", "").trim();
            if (clean.equalsIgnoreCase("NaN") || clean.equals("-")) return null;
            return Double.parseDouble(clean);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long parseLong(String val) {
        if (val == null || val.isBlank()) return null;
        try {
            String clean = val.replace("%", "").replace(",", "").trim();
            if (clean.equalsIgnoreCase("NaN") || clean.equals("-")) return null;
            return new java.math.BigDecimal(clean).longValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
