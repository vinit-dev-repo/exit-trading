package com.exittrading.app.service.core;

import com.exittrading.app.dto.SettingItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    private final Environment environment;
    private final LoggingSystem loggingSystem;
    private final Path settingsPath;
    private final Map<String, String> defaults;
    private final Map<String, String> overrides = new ConcurrentHashMap<>();
    private final Map<String, String> descriptions;
    private final Map<String, SettingType> types;

    public SettingsService(Environment environment, LoggingSystem loggingSystem,
                           @Value("${app.settings.file:config/runtime-settings.yml}") String settingsFile) {
        this.environment = environment;
        this.loggingSystem = loggingSystem;
        this.settingsPath = Paths.get(settingsFile);
        this.defaults = Collections.unmodifiableMap(loadDefaults());
        this.types = inferTypes(defaults);
        this.descriptions = SettingsDescriptions.descriptions();
        this.overrides.putAll(loadOverrides());
    }

    public List<SettingItem> list() {
        List<SettingItem> items = new ArrayList<>();
        for (String key : defaults.keySet()) {
            if (!isAllowedKey(key)) continue;
            String defaultValue = defaults.get(key);
            String overrideValue = overrides.get(key);
            String currentValue = overrideValue != null ? overrideValue : environment.getProperty(key, defaultValue);
            SettingType type = types.getOrDefault(key, SettingType.STRING);
            Object parsedCurrent = parseValue(currentValue, type);
            Object parsedDefault = parseValue(defaultValue, type);
            String description = descriptions.getOrDefault(key, "No description available.");
            String group = groupForKey(key);
            boolean overridden = overrideValue != null && !overrideValue.isBlank();
            items.add(new SettingItem(key, group, type.name().toLowerCase(Locale.ROOT),
                    parsedCurrent, parsedDefault, description, overridden));
        }
        return items.stream()
                .sorted(Comparator.comparing(SettingItem::group).thenComparing(SettingItem::key))
                .collect(Collectors.toList());
    }

    public synchronized void update(Map<String, Object> updates) {
        if (updates == null || updates.isEmpty()) return;
        Set<String> changedKeys = new java.util.HashSet<>();
        for (Map.Entry<String, Object> entry : updates.entrySet()) {
            String key = entry.getKey();
            if (!isAllowedKey(key)) continue;
            SettingType type = types.getOrDefault(key, SettingType.STRING);
            String normalized = normalize(entry.getValue(), type);
            String defaultValue = defaults.get(key);
            if (normalized == null || normalized.isBlank() || Objects.equals(normalized, defaultValue)) {
                overrides.remove(key);
            } else {
                overrides.put(key, normalized);
            }
            changedKeys.add(key);
        }
        persistOverrides();
        applyRuntimeHooks(changedKeys);
    }

    public synchronized void restoreDefaults() {
        overrides.clear();
        persistOverrides();
        applyRuntimeHooks(Set.copyOf(defaults.keySet()));
    }

    public String getString(String key, String fallback) {
        String override = overrides.get(key);
        if (override != null) return override;
        String envValue = environment.getProperty(key);
        return envValue != null ? envValue : fallback;
    }

    public int getInt(String key, int fallback) {
        String value = getString(key, null);
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public long getLong(String key, long fallback) {
        String value = getString(key, null);
        if (value == null || value.isBlank()) return fallback;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public double getDouble(String key, double fallback) {
        String value = getString(key, null);
        if (value == null || value.isBlank()) return fallback;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public boolean getBoolean(String key, boolean fallback) {
        String value = getString(key, null);
        if (value == null || value.isBlank()) return fallback;
        return Boolean.parseBoolean(value.trim());
    }

    private Map<String, String> loadDefaults() {
        Map<String, String> out = new TreeMap<>();
        try {
            YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
            yaml.setResources(new ClassPathResource("application.yml"));
            Properties props = yaml.getObject();
            if (props != null) {
                for (String name : props.stringPropertyNames()) {
                    if (isAllowedKey(name)) {
                        out.put(name, props.getProperty(name));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load defaults from application.yml: {}", e.getMessage());
        }
        try (InputStream in = new ClassPathResource("application.yml").getInputStream()) {
            Yaml yaml = new Yaml();
            Object data = yaml.load(in);
            if (data instanceof Map<?, ?> map) {
                Map<String, String> flat = new LinkedHashMap<>();
                flattenMap(map, "", flat);
                for (Map.Entry<String, String> entry : flat.entrySet()) {
                    String key = entry.getKey();
                    if (!isAllowedKey(key)) continue;
                    out.putIfAbsent(key, entry.getValue());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to flatten defaults from application.yml: {}", e.getMessage());
        }
        return out;
    }

    private Map<String, String> loadOverrides() {
        if (!Files.exists(settingsPath)) return Map.of();
        try (InputStream in = Files.newInputStream(settingsPath)) {
            Yaml yaml = new Yaml();
            Object data = yaml.load(in);
            if (!(data instanceof Map<?, ?> map)) return Map.of();
            Map<String, String> flat = new LinkedHashMap<>();
            flattenMap(map, "", flat);
            return flat.entrySet().stream()
                    .filter(e -> isAllowedKey(e.getKey()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        } catch (Exception e) {
            log.warn("Failed to load runtime settings from {}: {}", settingsPath, e.getMessage());
            return Map.of();
        }
    }

    private void persistOverrides() {
        try {
            Map<String, Object> nested = unflatten(overrides);
            Path parent = settingsPath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            options.setIndent(2);
            Yaml yaml = new Yaml(options);
            try (Writer writer = Files.newBufferedWriter(settingsPath, StandardCharsets.UTF_8)) {
                yaml.dump(nested, writer);
            }
        } catch (Exception e) {
            log.warn("Failed to persist runtime settings: {}", e.getMessage());
        }
    }

    private void applyRuntimeHooks(Set<String> keys) {
        if (keys == null || keys.isEmpty()) return;
        for (String key : keys) {
            if (!key.startsWith("logging.level.")) continue;
            String loggerName = key.substring("logging.level.".length());
            if ("root".equalsIgnoreCase(loggerName)) {
                loggerName = "ROOT";
            }
            String levelStr = getString(key, null);
            if (levelStr == null) continue;
            try {
                LogLevel level = LogLevel.valueOf(levelStr.trim().toUpperCase(Locale.ROOT));
                loggingSystem.setLogLevel(loggerName, level);
            } catch (Exception e) {
                log.debug("Failed to apply log level {} for {}: {}", levelStr, loggerName, e.getMessage());
            }
        }
    }

    private static void flattenMap(Map<?, ?> input, String prefix, Map<String, String> output) {
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            String key = entry.getKey() != null ? entry.getKey().toString() : "";
            String fullKey = prefix.isBlank() ? key : prefix + "." + key;
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> map) {
                flattenMap(map, fullKey, output);
            } else if (value != null) {
                output.put(fullKey, value.toString());
            }
        }
    }

    private Map<String, Object> unflatten(Map<String, String> flat) {
        Map<String, Object> root = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : flat.entrySet()) {
            String key = entry.getKey();
            if (!isAllowedKey(key)) continue;
            String[] parts = key.split("\\.");
            Map<String, Object> cursor = root;
            for (int i = 0; i < parts.length - 1; i++) {
                Object next = cursor.get(parts[i]);
                if (!(next instanceof Map<?, ?>)) {
                    Map<String, Object> created = new LinkedHashMap<>();
                    cursor.put(parts[i], created);
                    cursor = created;
                } else {
                    cursor = (Map<String, Object>) next;
                }
            }
            String leaf = parts[parts.length - 1];
            SettingType type = types.getOrDefault(key, SettingType.STRING);
            cursor.put(leaf, parseValue(entry.getValue(), type));
        }
        return root;
    }

    private static boolean isAllowedKey(String key) {
        if (key == null) return false;
        return !key.isBlank();
    }

    private static Map<String, SettingType> inferTypes(Map<String, String> defaults) {
        Map<String, SettingType> types = new ConcurrentHashMap<>();
        for (Map.Entry<String, String> entry : defaults.entrySet()) {
            types.put(entry.getKey(), inferType(entry.getValue()));
        }
        return types;
    }

    private static SettingType inferType(String value) {
        if (value == null) return SettingType.STRING;
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(trimmed) || "false".equals(trimmed)) {
            return SettingType.BOOLEAN;
        }
        if (trimmed.matches("[-+]?\\d+(\\.\\d+)?")) {
            return SettingType.NUMBER;
        }
        return SettingType.STRING;
    }

    private static Object parseValue(String value, SettingType type) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isBlank()) return null;
        try {
            return switch (type) {
                case BOOLEAN -> Boolean.parseBoolean(trimmed);
                case NUMBER -> {
                    BigDecimal bd = new BigDecimal(trimmed);
                    yield bd.scale() <= 0 ? bd.longValue() : bd.doubleValue();
                }
                case STRING -> trimmed;
            };
        } catch (Exception e) {
            return trimmed;
        }
    }

    private static String normalize(Object value, SettingType type) {
        if (value == null) return null;
        String str = value.toString().trim();
        if (str.isBlank()) return null;
        return switch (type) {
            case BOOLEAN -> String.valueOf(Boolean.parseBoolean(str));
            case NUMBER -> {
                try {
                    BigDecimal bd = new BigDecimal(str);
                    yield bd.stripTrailingZeros().toPlainString();
                } catch (Exception e) {
                    yield str;
                }
            }
            case STRING -> str;
        };
    }

    private static String groupForKey(String key) {
        if (key.startsWith("server.")) return "Server";
        if (key.startsWith("logging.")) return "Logging";
        if (key.startsWith("kite.")) return "Kite";
        if (key.startsWith("management.")) return "Management";
        if (key.startsWith("auction.detector.")) return "Auction Detector";
        if (key.startsWith("market.depth.")) return "Market Depth";
        if (key.startsWith("app.config.esm.")) return "ESM";
        if (key.startsWith("app.config.market.")) return "Market";
        if (key.startsWith("app.config.scheduler.")) return "Scheduler";
        if (key.startsWith("app.config.ingestion.")) return "Ingestion";
        if (key.startsWith("spring.datasource.")) return "Database";
        if (key.startsWith("spring.jpa.")) return "Database";
        if (key.startsWith("spring.sql.")) return "Database";
        if (key.startsWith("spring.jackson.")) return "Serialization";
        if (key.startsWith("spring.")) return "Spring";
        return "Other";
    }

    private enum SettingType { BOOLEAN, NUMBER, STRING }

    private static final class SettingsDescriptions {
        private static Map<String, String> descriptions() {
            Map<String, String> map = new LinkedHashMap<>();
            map.put("logging.level.root", "Global log level for the entire application.");
            map.put("logging.level.com.exittrading", "Log level for ExitTrading application code.");
            map.put("logging.level.ticker", "Log level for ticker/depth streaming logs.");
            map.put("logging.level.ImpactLog", "Log level for impact analysis logs.");
            map.put("logging.file.name", "Log file path for application logs (restart may be required).");
            map.put("server.port", "HTTP port for the web application.");
            map.put("server.servlet.session.cookie.http-only", "Prevent JavaScript from accessing session cookies.");
            map.put("server.error.include-message", "Include error messages in API responses.");
            map.put("kite.enabled", "Enable or disable Kite Connect integration.");
            map.put("kite.apiKey", "Kite API key used for authentication.");
            map.put("kite.apiSecret", "Kite API secret used for authentication.");
            map.put("kite.default.exchange", "Default exchange used for instrument lookup.");
            map.put("management.endpoints.web.exposure.include", "Actuator endpoints exposed over HTTP.");
            map.put("auction.detector.levels", "Depth levels used for auction detection.");
            map.put("auction.detector.windowSec", "Time window (seconds) for auction signal analysis.");
            map.put("auction.detector.ewmaHalfLifeSec", "EWMA half-life in seconds for pressure smoothing.");
            map.put("auction.detector.scoreTrigger", "Auction score threshold to trigger an alert.");
            map.put("auction.detector.swingConfirm", "Minimum swing magnitude to confirm a trend.");
            map.put("auction.detector.nearLtpBps", "Basis points threshold to treat LTP as near lower circuit.");
            map.put("auction.detector.actionWindowStartSec", "Seconds after which action logic becomes active.");
            map.put("auction.detector.safetySec", "Safety margin in seconds before window close.");
            map.put("auction.detector.session-drift-refresh-sec", "Seconds between session drift recalculations.");
            map.put("auction.detector.session-window-min", "Minutes per session window for drift analysis.");
            map.put("spring.datasource.url", "JDBC URL for the database connection.");
            map.put("spring.datasource.username", "Database username.");
            map.put("spring.datasource.password", "Database password.");
            map.put("spring.datasource.driverClassName", "JDBC driver class name.");
            map.put("spring.datasource.hikari.maximum-pool-size", "Maximum HikariCP connection pool size.");
            map.put("spring.sql.init.mode", "Control execution of schema/data SQL on startup.");
            map.put("spring.jpa.hibernate.ddl-auto", "Hibernate schema management strategy.");
            map.put("spring.jpa.open-in-view", "Keep Hibernate session open for web requests.");
            map.put("spring.jpa.properties.hibernate.default_schema", "Default database schema for Hibernate.");
            map.put("spring.jpa.properties.hibernate.format_sql", "Pretty-print SQL in logs.");
            map.put("spring.jackson.time-zone", "Default timezone for JSON serialization.");
            map.put("market.depth.suppress.sell-pressure-threshold", "Sell pressure percent above which persistence is suppressed.");
            map.put("market.depth.suppress.start-time", "Time (HH:mm) after which suppression is allowed.");
            map.put("market.depth.suppress.downtrend-enabled", "Enable suppression when downtrend and sell pressure conditions are met.");
            map.put("market.depth.suppress.downtrend-sell-pressure-threshold", "Sell pressure percent to suppress during downtrend.");
            map.put("market.depth.suppress.downtrend-ltp-drop-bps", "Minimum basis-point drop vs prev close/open to qualify as downtrend.");
            map.put("app.config.esm.auto-exit-enabled", "Master toggle for auto-exit order placement.");
            map.put("app.config.esm.monitor-lead-minutes", "Minutes before slot to start ESM monitoring.");
            map.put("app.config.esm.session-length-minutes", "Length of an ESM session in minutes.");
            map.put("app.config.esm.hazard-start-minutes", "Minutes after session start when hazard zone begins.");
            map.put("app.config.esm.hazard-timeout-seconds", "Timeout seconds after hazard start.");
            map.put("app.config.esm.deadline-seconds", "Buffer seconds after session end before expiry.");
            map.put("app.config.esm.sniper-prefire-ms", "Prefire offset in ms for sniper rollover.");
            map.put("app.config.esm.snapshot-stale-sec", "Seconds after which depth snapshot is considered stale.");
            map.put("app.config.market.debounce-ms", "Minimum interval between DB writes per scrip.");
            map.put("app.config.market.polling-interval-ms", "Polling interval for REST fallback capture.");
            map.put("app.config.market.significant-change-pct", "Percent change required to mark a move significant.");
            map.put("app.config.market.max-age-ms", "Max age before persisting a heartbeat snapshot.");
            map.put("app.config.market.history-window-sec", "Seconds of tick history retained in memory.");
            map.put("app.config.scheduler.staleness-threshold-min", "Minutes after which a schedule is considered stale.");
            map.put("app.config.scheduler.restart-window-sec", "Seconds window to allow restarting overdue tasks.");
            map.put("app.config.scheduler.cancel-window-sec", "Seconds before execution to cancel open orders.");
            map.put("app.config.ingestion.batch-size", "CSV ingestion batch size per transaction.");
            return map;
        }
    }
}
