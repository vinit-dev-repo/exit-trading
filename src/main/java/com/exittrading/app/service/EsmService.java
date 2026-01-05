package com.exittrading.app.service;

import com.opencsv.CSVReaderHeaderAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class EsmService {

    private static final Logger logger = LoggerFactory.getLogger(EsmService.class);
    private static final String SCRIPTS_DIR = "scripts";
    private static final String TEMP_DIR = "data/temp_esm";

    private final com.exittrading.app.service.ingestion.CsvIngestionService csvIngestionService;
    private final com.exittrading.app.service.scrip.LoggingScripService loggingScripService;
    private final com.exittrading.app.service.core.KiteSessionManager sessionManager;
    private final com.exittrading.app.repository.InstrumentRepository instrumentRepository;
    private final AtomicReference<EsmStatus> currentStatus = new AtomicReference<>(new EsmStatus());
    private final java.util.concurrent.atomic.AtomicBoolean isProcessing = new java.util.concurrent.atomic.AtomicBoolean(false);

    private final com.exittrading.app.service.core.InstrumentService instrumentService; // Added field

    public EsmService(com.exittrading.app.service.ingestion.CsvIngestionService csvIngestionService,
                      com.exittrading.app.service.scrip.LoggingScripService loggingScripService,
                      com.exittrading.app.service.core.KiteSessionManager sessionManager,
                      com.exittrading.app.repository.InstrumentRepository instrumentRepository,
                      com.exittrading.app.service.core.InstrumentService instrumentService) { // Added param
        this.csvIngestionService = csvIngestionService;
        this.loggingScripService = loggingScripService;
        this.sessionManager = sessionManager;
        this.instrumentRepository = instrumentRepository;
        this.instrumentService = instrumentService; // Assign
    }

    public EsmStatus getStatus() {
        return currentStatus.get();
    }

    public com.exittrading.app.dto.EsmResult processEsmFiles(MultipartFile bseFile, MultipartFile nseFile, MultipartFile reportFile) throws IOException {
        if (!isProcessing.compareAndSet(false, true)) {
            throw new RuntimeException("Job in progress. Please wait.");
        }

        Path bsePath = null;
        Path nsePath = null;
        Path outputPath = null;

        try {
            currentStatus.set(new EsmStatus());
            Path tempDir = Paths.get(TEMP_DIR);
            if (!Files.exists(tempDir)) Files.createDirectories(tempDir);

            String runId = UUID.randomUUID().toString();
            String outputName = "esm_report_" + runId + ".csv";
            outputPath = tempDir.resolve(outputName);

            // DIRECT REPORT UPLOAD PATH
            if (reportFile != null && !reportFile.isEmpty()) {
                logger.info("Using uploaded report file directly.");
                reportFile.transferTo(outputPath);
            } 
            // GENERATION PATH
            else {
                if (bseFile != null && !bseFile.isEmpty()) {
                    bsePath = tempDir.resolve("bse_" + runId + ".csv");
                    bseFile.transferTo(bsePath);
                }
                if (nseFile != null && !nseFile.isEmpty()) {
                    nsePath = tempDir.resolve("nse_" + runId + ".csv");
                    nseFile.transferTo(nsePath);
                }
                if (bsePath == null && nsePath == null) {
                    throw new IllegalArgumentException("Provide BSE+NSE files OR a Final Report file.");
                }

                List<String> command = new ArrayList<>();
                command.add("python");
                command.add(SCRIPTS_DIR + File.separator + "esm_screener.py");
                if (bsePath != null) { command.add("--bse"); command.add(bsePath.toAbsolutePath().toString()); }
                if (nsePath != null) { command.add("--nse"); command.add(nsePath.toAbsolutePath().toString()); }
                command.add("--output");
                command.add(outputPath.toAbsolutePath().toString());

                logger.info("Executing ESM Screener...");
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                Process process = pb.start();

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    java.util.regex.Pattern progressPattern = java.util.regex.Pattern.compile("Processing (\\d+)/(\\d+):");
                    while ((line = reader.readLine()) != null) {
                        logger.info("[ESM_PYTHON] {}", line);
                        EsmStatus stat = currentStatus.get();
                        List<String> newLogs = new ArrayList<>(stat.logs);
                        newLogs.add(line);
                        String msg = stat.message;
                        int p = stat.processed; 
                        int t = stat.total;
                        java.util.regex.Matcher m = progressPattern.matcher(line);
                        if (m.find()) {
                            p = Integer.parseInt(m.group(1));
                            t = Integer.parseInt(m.group(2));
                            msg = line;
                        }
                        currentStatus.set(new EsmStatus(msg, p, t, newLogs));
                    }
                }
                if (process.waitFor() != 0) throw new RuntimeException("Script failed.");
                if (!Files.exists(outputPath)) throw new RuntimeException("Output missing.");
            }

            // ANALYSIS PHASE: Find New Scrips
            List<com.exittrading.app.dto.ScripAnalysis> analysis = analyzeScrips(outputPath);
            
            return new com.exittrading.app.dto.EsmResult(outputName, analysis, "Analysis Complete. " + analysis.size() + " scrips processed.");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted", e);
        } finally {
            try {
                if (bsePath != null) Files.deleteIfExists(bsePath);
                if (nsePath != null) Files.deleteIfExists(nsePath);
            } catch (IOException e) { logger.warn("Cleanup failed", e); }
            isProcessing.set(false);
        }
    }
    
    public void confirmIngestion(String fileName, Map<String, String> corrections) {
         Path path = Paths.get(TEMP_DIR).resolve(fileName);
         if (!Files.exists(path)) throw new RuntimeException("File expired or not found: " + fileName);
         
         try {
             logger.info("Confirming ingestion for: {}", fileName);
             csvIngestionService.ingestReport(path, LocalDate.now(), corrections);
             
             String currentUser = sessionManager.getUserName();
             if (currentUser != null && !currentUser.isBlank()) {
                 loggingScripService.syncInstruments(currentUser);
             }
         } catch (Exception e) {
             throw new RuntimeException("Ingestion failed: " + e.getMessage(), e);
         }
    }
    
    public Resource getDownloadResource(String fileName) {
        Path path = Paths.get(TEMP_DIR).resolve(fileName);
        if (!Files.exists(path)) return null;
        return new FileSystemResource(path.toFile());
    }

    private List<com.exittrading.app.dto.ScripAnalysis> analyzeScrips(Path csvPath) {
        List<com.exittrading.app.dto.ScripAnalysis> results = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();
        
        try (CSVReaderHeaderAware reader = new CSVReaderHeaderAware(new FileReader(csvPath.toFile()))) {
            Map<String, String> values;
            while ((values = reader.readMap()) != null) {
                String sym = values.get("Symbol");
                String exch = values.get("Exchange");
                if (sym != null && !sym.isBlank()) {
                    rows.add(new String[]{sym.trim(), exch});
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to parse report for analysis", e);
            return List.of();
        }
        
        for (String[] row : rows) {
            String sym = row[0];
            String exch = row[1]; // might be null or BSE/NSE
            if (exch == null || exch.isBlank()) exch = "NSE"; // default
            
            // Check DB first
            var existing = instrumentRepository.findBySymbol(sym);
            if (existing != null) {
                // If it exists but has Token 0 or null, treat as UNKNOWN so user can fix it
                if (existing.getToken() != null && existing.getToken() != 0L) {
                    results.add(new com.exittrading.app.dto.ScripAnalysis(sym, exch, sym, String.valueOf(existing.getToken()), "EXISTING"));
                    continue; 
                }
                // Else fall through to master check/unknown logic
            }

            // Check Master (Kite)
            var meta = instrumentService.find(exch, sym, null);
            if (meta != null && meta.token != null) {
                 results.add(new com.exittrading.app.dto.ScripAnalysis(sym, exch, sym, meta.token, "FOUND"));
            } else {
                 results.add(new com.exittrading.app.dto.ScripAnalysis(sym, exch, "", null, "UNKNOWN"));
            }
        }
        
        return results;
    }

    /**
     * Periodically clean up temp ESM reports older than 1 hour.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 3600000)
    public void cleanupTempFiles() {
        try {
            Path tempDir = Paths.get(TEMP_DIR);
            if (!Files.exists(tempDir)) return;
            java.time.Instant cutoff = java.time.Instant.now().minus(java.time.Duration.ofHours(1));
            try (java.util.stream.Stream<Path> files = Files.list(tempDir)) {
                files.filter(Files::isRegularFile)
                     .filter(p -> { try { return Files.getLastModifiedTime(p).toInstant().isBefore(cutoff); } catch (IOException e) { return false; } })
                     .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException e) {} });
            }
        } catch (IOException e) {}
    }

    private Double parseDouble(String value) { // Legacy helper kept if needed
        return null;
    }

    public static class EsmStatus {
        public final String message;
        public final int processed;
        public final int total;
        public final List<String> logs;
        public EsmStatus() { this("Idle", 0, 0, new ArrayList<>()); }
        public EsmStatus(String message, int processed, int total, List<String> logs) {
            this.message = message; this.processed = processed; this.total = total; this.logs = logs;
        }
    }
}
