package com.exittrading.app.service.screener;

import com.exittrading.app.domain.ScreenerPreset;
import com.exittrading.app.domain.UserAccount;
import com.exittrading.app.dto.*;
import com.exittrading.app.repository.ScreenerPresetRepository;
import com.exittrading.app.repository.UserAccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class ScreenerService {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 500;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final UserAccountRepository userRepository;
    private final ScreenerPresetRepository presetRepository;
    private final ObjectMapper objectMapper;

    private static final Map<String, ColumnMeta> COLUMN_MAP = buildColumns();
    private static final List<String> DEFAULT_COLUMNS = List.of(
            "symbol", "exchange", "currentPrice", "peRatio", "roe", "debtToEquity",
            "promoterPct", "dma50", "dma200", "return1wk", "volume", "marketCap"
    );

    public ScreenerService(NamedParameterJdbcTemplate jdbcTemplate,
                           UserAccountRepository userRepository,
                           ScreenerPresetRepository presetRepository,
                           ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.userRepository = userRepository;
        this.presetRepository = presetRepository;
        this.objectMapper = objectMapper;
    }

    public List<ScreenerColumn> getColumns() {
        return COLUMN_MAP.values().stream()
                .filter(ColumnMeta::visible)
                .sorted(Comparator.comparing(ColumnMeta::group).thenComparing(ColumnMeta::label))
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public String latestReportDate() {
        String sql = """
                SELECT MAX(report_date) AS report_date FROM (
                  SELECT report_date FROM daily_quotes
                  UNION ALL SELECT report_date FROM daily_valuations
                  UNION ALL SELECT report_date FROM shareholding_patterns
                  UNION ALL SELECT report_date FROM technical_indicators
                ) d
                """;
        LocalDate date = jdbcTemplate.queryForObject(sql, new MapSqlParameterSource(), LocalDate.class);
        return date != null ? date.toString() : null;
    }

    public ScreenerQueryResponse query(String username, ScreenerQueryRequest request) {
        requireUser(username);
        LocalDate reportDate = resolveReportDate(request.reportDate());
        int limit = clamp(request.limit(), DEFAULT_LIMIT, MAX_LIMIT);
        int offset = Math.max(0, request.offset() != null ? request.offset() : 0);

        List<String> requestedColumns = sanitizeColumns(request.columns());
        List<ScreenerComputedColumn> computedColumns = sanitizeComputedColumns(request.computedColumns());
        ComputedBundle computedBundle = compileComputedColumns(computedColumns);

        LinkedHashSet<String> selectKeys = new LinkedHashSet<>();
        selectKeys.add("symbol");
        selectKeys.addAll(requestedColumns);
        selectKeys.addAll(computedBundle.requiredKeys);
        selectKeys.add("sourceUrl");

        String selectClause = selectKeys.stream()
                .map(this::selectExpressionForKey)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(", "));

        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("reportDate", reportDate);
        String whereClause = buildWhereClause(request.filters(), params);
        String baseWhere = "dq.report_date = :reportDate";
        if (whereClause != null && !whereClause.isBlank()) {
            baseWhere = baseWhere + " AND " + whereClause;
        }

        String sortClause = buildSortClause(request.sort());
        String sql = """
                SELECT %s
                FROM daily_quotes dq
                LEFT JOIN daily_valuations dv ON dv.report_date = dq.report_date AND dv.symbol = dq.symbol
                LEFT JOIN shareholding_patterns sp ON sp.report_date = dq.report_date AND sp.symbol = dq.symbol
                LEFT JOIN technical_indicators ti ON ti.report_date = dq.report_date AND ti.symbol = dq.symbol
                LEFT JOIN instruments i ON i.token = dq.token
                WHERE %s
                %s
                LIMIT :limit OFFSET :offset
                """.formatted(selectClause, baseWhere, sortClause);

        params.addValue("limit", limit);
        params.addValue("offset", offset);

        List<String> rowKeys = selectKeys.stream()
                .filter(key -> selectExpressionForKey(key) != null)
                .collect(Collectors.toList());

        List<Map<String, Object>> rows = jdbcTemplate.query(sql, params, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            for (String key : rowKeys) {
                row.put(key, rs.getObject(key));
            }
            return row;
        });

        if (!computedBundle.compiled.isEmpty()) {
            for (Map<String, Object> row : rows) {
                for (CompiledComputedColumn col : computedBundle.compiled) {
                    Double value = ExpressionEngine.evaluate(col.expression, row);
                    row.put(col.key, value);
                }
            }
        }

        long total = countTotal(baseWhere, params);
        boolean hasMore = (offset + rows.size()) < total;

        List<ScreenerColumn> responseColumns = buildResponseColumns(requestedColumns);
        return new ScreenerQueryResponse(
                reportDate.toString(),
                responseColumns,
                computedBundle.original,
                rows,
                total,
                hasMore,
                offset,
                limit
        );
    }

    public List<ScreenerPresetDto> listPresets(String username) {
        UserAccount user = requireUser(username);
        return presetRepository.findByUserOrderByNameAsc(user).stream()
                .map(this::toPresetDto)
                .collect(Collectors.toList());
    }

    public ScreenerPresetDto createPreset(String username, ScreenerPresetRequest request) {
        UserAccount user = requireUser(username);
        ScreenerPreset preset = new ScreenerPreset();
        preset.setUser(user);
        preset.setName(normalizeName(request.name()));
        preset.setConfigJson(serializeConfig(request.config()));
        return toPresetDto(presetRepository.save(preset));
    }

    public ScreenerPresetDto updatePreset(String username, Long id, ScreenerPresetRequest request) {
        UserAccount user = requireUser(username);
        ScreenerPreset preset = presetRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new IllegalArgumentException("Preset not found."));
        preset.setName(normalizeName(request.name()));
        preset.setConfigJson(serializeConfig(request.config()));
        return toPresetDto(presetRepository.save(preset));
    }

    public void deletePreset(String username, Long id) {
        UserAccount user = requireUser(username);
        ScreenerPreset preset = presetRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new IllegalArgumentException("Preset not found."));
        presetRepository.delete(preset);
    }

    private long countTotal(String baseWhere, MapSqlParameterSource params) {
        String countSql = """
                SELECT COUNT(*)
                FROM daily_quotes dq
                LEFT JOIN daily_valuations dv ON dv.report_date = dq.report_date AND dv.symbol = dq.symbol
                LEFT JOIN shareholding_patterns sp ON sp.report_date = dq.report_date AND sp.symbol = dq.symbol
                LEFT JOIN technical_indicators ti ON ti.report_date = dq.report_date AND ti.symbol = dq.symbol
                LEFT JOIN instruments i ON i.token = dq.token
                WHERE %s
                """.formatted(baseWhere);
        return Optional.ofNullable(jdbcTemplate.queryForObject(countSql, params, Long.class)).orElse(0L);
    }

    private ScreenerColumn toDto(ColumnMeta meta) {
        return new ScreenerColumn(meta.key, meta.label, meta.type.name().toLowerCase(Locale.ROOT),
                meta.group, meta.sortable, meta.filterable);
    }

    private List<String> sanitizeColumns(List<String> requested) {
        List<String> base = requested == null || requested.isEmpty() ? DEFAULT_COLUMNS : requested;
        List<String> filtered = base.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(key -> COLUMN_MAP.containsKey(key))
                .filter(key -> !"sourceUrl".equals(key))
                .distinct()
                .collect(Collectors.toList());
        if (!filtered.contains("symbol")) {
            filtered.add(0, "symbol");
        }
        return filtered;
    }

    private List<ScreenerComputedColumn> sanitizeComputedColumns(List<ScreenerComputedColumn> computed) {
        if (computed == null) return List.of();
        List<ScreenerComputedColumn> clean = new ArrayList<>();
        for (ScreenerComputedColumn col : computed) {
            if (col == null) continue;
            String key = normalizeKey(col.key());
            if (key == null) continue;
            String label = col.label() == null || col.label().isBlank() ? key : col.label().trim();
            String expr = col.expression() != null ? col.expression().trim() : "";
            if (expr.isBlank()) continue;
            clean.add(new ScreenerComputedColumn(key, label, expr));
        }
        return clean;
    }

    private ComputedBundle compileComputedColumns(List<ScreenerComputedColumn> computed) {
        if (computed == null || computed.isEmpty()) {
            return new ComputedBundle(List.of(), List.of(), Set.of());
        }
        List<CompiledComputedColumn> compiled = new ArrayList<>();
        Set<String> requiredKeys = new LinkedHashSet<>();
        List<ScreenerComputedColumn> accepted = new ArrayList<>();
        for (ScreenerComputedColumn col : computed) {
            try {
                ExpressionEngine.CompileResult result = ExpressionEngine.compile(col.expression(), COLUMN_MAP);
                if (result == null) continue;
                requiredKeys.addAll(result.requiredKeys());
                compiled.add(new CompiledComputedColumn(col.key(), result.expression()));
                accepted.add(col);
            } catch (Exception ignored) {
            }
        }
        return new ComputedBundle(accepted, compiled, requiredKeys);
    }

    private List<ScreenerColumn> buildResponseColumns(List<String> requestedColumns) {
        List<ScreenerColumn> cols = new ArrayList<>();
        for (String key : requestedColumns) {
            ColumnMeta meta = COLUMN_MAP.get(key);
            if (meta == null || !meta.visible) continue;
            cols.add(toDto(meta));
        }
        return cols;
    }

    private String selectExpressionForKey(String key) {
        if ("sourceUrl".equals(key)) {
            return "i.source_url AS sourceUrl";
        }
        ColumnMeta meta = COLUMN_MAP.get(key);
        if (meta == null) return null;
        return meta.sql + " AS " + meta.key;
    }

    private String buildSortClause(ScreenerSort sort) {
        String key = sort != null ? sort.key() : null;
        ColumnMeta meta = key != null ? COLUMN_MAP.get(key) : null;
        String expr = meta != null && meta.sortable ? meta.sql : COLUMN_MAP.get("symbol").sql;
        String dir = sort != null && "desc".equalsIgnoreCase(sort.direction()) ? "DESC" : "ASC";
        return "ORDER BY " + expr + " " + dir + " NULLS LAST";
    }

    private String buildWhereClause(ScreenerFilter filter, MapSqlParameterSource params) {
        if (filter == null) return null;
        AtomicInteger index = new AtomicInteger(0);
        return buildFilterNode(filter, params, index);
    }

    private String buildFilterNode(ScreenerFilter filter, MapSqlParameterSource params, AtomicInteger index) {
        if (filter == null || filter.getType() == null) return null;
        String type = filter.getType().toLowerCase(Locale.ROOT);
        if ("group".equals(type)) {
            String op = "AND";
            if ("or".equalsIgnoreCase(filter.getOperator())) op = "OR";
            List<String> parts = new ArrayList<>();
            for (ScreenerFilter child : filter.getChildren()) {
                String fragment = buildFilterNode(child, params, index);
                if (fragment != null && !fragment.isBlank()) {
                    parts.add(fragment);
                }
            }
            if (parts.isEmpty()) return null;
            return parts.size() == 1 ? parts.get(0) : "(" + String.join(" " + op + " ", parts) + ")";
        }

        if (!"condition".equals(type)) return null;
        String field = filter.getField();
        ColumnMeta meta = field != null ? COLUMN_MAP.get(field) : null;
        if (meta == null || !meta.filterable) return null;
        String op = filter.getOp() != null ? filter.getOp().trim().toLowerCase(Locale.ROOT) : "=";
        String expr = meta.sql;

        if ("isnull".equals(op)) {
            return expr + " IS NULL";
        }
        if ("isnotnull".equals(op)) {
            return expr + " IS NOT NULL";
        }

        if ("between".equals(op)) {
            List<Object> values = parseList(filter.getValue());
            if (values.size() < 2) return null;
            Object start = coerceValue(meta.type, values.get(0));
            Object end = coerceValue(meta.type, values.get(1));
            if (start == null || end == null) return null;
            String p1 = "p" + index.incrementAndGet();
            String p2 = "p" + index.incrementAndGet();
            params.addValue(p1, start);
            params.addValue(p2, end);
            return expr + " BETWEEN :" + p1 + " AND :" + p2;
        }

        if ("in".equals(op)) {
            List<Object> values = parseList(filter.getValue()).stream()
                    .map(v -> coerceValue(meta.type, v))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            if (values.isEmpty()) return null;
            String p = "p" + index.incrementAndGet();
            params.addValue(p, values);
            return expr + " IN (:" + p + ")";
        }

        Object value = coerceValue(meta.type, filter.getValue());
        if (value == null) return null;

        if ((op.equals("contains") || op.equals("startswith") || op.equals("endswith"))
                && meta.type != ColumnType.STRING) {
            return null;
        }

        if ("contains".equals(op)) {
            String p = "p" + index.incrementAndGet();
            params.addValue(p, "%" + value + "%");
            return expr + " ILIKE :" + p;
        }
        if ("startswith".equals(op)) {
            String p = "p" + index.incrementAndGet();
            params.addValue(p, value + "%");
            return expr + " ILIKE :" + p;
        }
        if ("endswith".equals(op)) {
            String p = "p" + index.incrementAndGet();
            params.addValue(p, "%" + value);
            return expr + " ILIKE :" + p;
        }

        String sqlOp = switch (op) {
            case "!=", "<>" -> "<>";
            case ">", ">=", "<", "<=", "=" -> op;
            default -> "=";
        };
        String p = "p" + index.incrementAndGet();
        params.addValue(p, value);
        return expr + " " + sqlOp + " :" + p;
    }

    private Object coerceValue(ColumnType type, Object value) {
        if (value == null) return null;
        String str = value.toString().trim();
        if (str.isBlank()) return null;
        try {
            return switch (type) {
                case NUMBER -> Double.valueOf(str.replace(",", ""));
                case DATE -> LocalDate.parse(str);
                case STRING -> str;
            };
        } catch (Exception e) {
            return null;
        }
    }

    private List<Object> parseList(Object value) {
        if (value == null) return List.of();
        if (value instanceof List<?> list) return new ArrayList<>(list);
        String str = value.toString();
        if (!str.contains(",")) return List.of(str.trim());
        String[] parts = str.split(",");
        List<Object> out = new ArrayList<>();
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isBlank()) out.add(trimmed);
        }
        return out;
    }

    private LocalDate resolveReportDate(String reportDate) {
        if (reportDate != null && !reportDate.isBlank()) {
            return LocalDate.parse(reportDate);
        }
        String latest = latestReportDate();
        if (latest != null) {
            return LocalDate.parse(latest);
        }
        return LocalDate.now();
    }

    private int clamp(Integer value, int fallback, int max) {
        int v = value != null ? value : fallback;
        if (v < 1) v = fallback;
        return Math.min(v, max);
    }

    private UserAccount requireUser(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("User is required.");
        }
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Unknown user."));
    }

    private ScreenerPresetDto toPresetDto(ScreenerPreset preset) {
        ScreenerQueryRequest config = deserializeConfig(preset.getConfigJson());
        return new ScreenerPresetDto(preset.getId(), preset.getName(), config);
    }

    private String normalizeName(String name) {
        String safe = name != null ? name.trim() : "";
        if (safe.isBlank()) {
            throw new IllegalArgumentException("Preset name is required.");
        }
        return safe;
    }

    private String serializeConfig(ScreenerQueryRequest config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to serialize preset configuration.");
        }
    }

    private ScreenerQueryRequest deserializeConfig(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, ScreenerQueryRequest.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String normalizeKey(String key) {
        if (key == null) return null;
        String trimmed = key.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isBlank()) return null;
        String cleaned = trimmed.replaceAll("[^a-z0-9_]", "_");
        if (!cleaned.startsWith("calc_")) cleaned = "calc_" + cleaned;
        return cleaned;
    }

    private static Map<String, ColumnMeta> buildColumns() {
        List<ColumnMeta> columns = new ArrayList<>();
        columns.add(ColumnMeta.visible("symbol", "Symbol", ColumnType.STRING, "Core", "dq.symbol", true, true));
        columns.add(ColumnMeta.visible("reportDate", "Report Date", ColumnType.DATE, "Core", "dq.report_date", true, true));
        columns.add(ColumnMeta.visible("exchange", "Exchange", ColumnType.STRING, "Instrument", "i.exchange", true, true));
        columns.add(ColumnMeta.visible("name", "Name", ColumnType.STRING, "Instrument", "i.name", true, true));
        columns.add(ColumnMeta.visible("industry", "Industry", ColumnType.STRING, "Instrument", "i.industry", true, true));
        columns.add(ColumnMeta.visible("faceValue", "Face Value", ColumnType.NUMBER, "Instrument", "i.face_value", true, true));
        columns.add(ColumnMeta.visible("currentPrice", "Price", ColumnType.NUMBER, "Price", "dq.current_price", true, true));
        columns.add(ColumnMeta.visible("highPrice", "High", ColumnType.NUMBER, "Price", "dq.high_price", true, true));
        columns.add(ColumnMeta.visible("lowPrice", "Low", ColumnType.NUMBER, "Price", "dq.low_price", true, true));
        columns.add(ColumnMeta.visible("volume", "Volume", ColumnType.NUMBER, "Price", "dq.volume", true, true));
        columns.add(ColumnMeta.visible("marketCap", "Market Cap", ColumnType.NUMBER, "Price", "dq.market_cap", true, true));
        columns.add(ColumnMeta.visible("peRatio", "P/E", ColumnType.NUMBER, "Valuation", "dv.pe_ratio", true, true));
        columns.add(ColumnMeta.visible("bookValue", "Book Value", ColumnType.NUMBER, "Valuation", "dv.book_value", true, true));
        columns.add(ColumnMeta.visible("dividendYield", "Dividend Yield", ColumnType.NUMBER, "Valuation", "dv.dividend_yield", true, true));
        columns.add(ColumnMeta.visible("roce", "ROCE", ColumnType.NUMBER, "Valuation", "dv.roce", true, true));
        columns.add(ColumnMeta.visible("roe", "ROE", ColumnType.NUMBER, "Valuation", "dv.roe", true, true));
        columns.add(ColumnMeta.visible("debtToEquity", "Debt/Equity", ColumnType.NUMBER, "Valuation", "dv.debt_to_equity", true, true));
        columns.add(ColumnMeta.visible("piotroskiScore", "Piotroski", ColumnType.NUMBER, "Valuation", "dv.piotroski_score", true, true));
        columns.add(ColumnMeta.visible("qtrSalesVar", "Qtr Sales Var", ColumnType.NUMBER, "Valuation", "dv.qtr_sales_var", true, true));
        columns.add(ColumnMeta.visible("qtrProfitVar", "Qtr Profit Var", ColumnType.NUMBER, "Valuation", "dv.qtr_profit_var", true, true));
        columns.add(ColumnMeta.visible("promoterPct", "Promoter %", ColumnType.NUMBER, "Ownership", "sp.promoter_pct", true, true));
        columns.add(ColumnMeta.visible("publicPct", "Public %", ColumnType.NUMBER, "Ownership", "sp.public_pct", true, true));
        columns.add(ColumnMeta.visible("pledgedPct", "Pledged %", ColumnType.NUMBER, "Ownership", "sp.pledged_pct", true, true));
        columns.add(ColumnMeta.visible("changePromHold", "Change Prom Hold", ColumnType.NUMBER, "Ownership", "sp.change_prom_hold", true, true));
        columns.add(ColumnMeta.visible("dma50", "DMA 50", ColumnType.NUMBER, "Technicals", "ti.dma_50", true, true));
        columns.add(ColumnMeta.visible("dma200", "DMA 200", ColumnType.NUMBER, "Technicals", "ti.dma_200", true, true));
        columns.add(ColumnMeta.visible("avgVol1wk", "Avg Vol 1Wk", ColumnType.NUMBER, "Technicals", "ti.avg_vol_1wk", true, true));
        columns.add(ColumnMeta.visible("avgVol1mth", "Avg Vol 1Mth", ColumnType.NUMBER, "Technicals", "ti.avg_vol_1mth", true, true));
        columns.add(ColumnMeta.visible("return1d", "Return 1D", ColumnType.NUMBER, "Technicals", "ti.return_1d", true, true));
        columns.add(ColumnMeta.visible("return1wk", "Return 1Wk", ColumnType.NUMBER, "Technicals", "ti.return_1wk", true, true));
        columns.add(ColumnMeta.hidden("sourceUrl", ColumnType.STRING, "i.source_url"));

        Map<String, ColumnMeta> map = new LinkedHashMap<>();
        for (ColumnMeta meta : columns) {
            map.put(meta.key, meta);
        }
        return map;
    }

    private record CompiledComputedColumn(String key, ExpressionEngine.CompiledExpression expression) {
    }

    private record ComputedBundle(List<ScreenerComputedColumn> original,
                                  List<CompiledComputedColumn> compiled,
                                  Set<String> requiredKeys) {
    }

    private enum ColumnType { STRING, NUMBER, DATE }

    private static final class ColumnMeta {
        private final String key;
        private final String label;
        private final ColumnType type;
        private final String group;
        private final String sql;
        private final boolean sortable;
        private final boolean filterable;
        private final boolean visible;

        private ColumnMeta(String key, String label, ColumnType type, String group, String sql,
                           boolean sortable, boolean filterable, boolean visible) {
            this.key = key;
            this.label = label;
            this.type = type;
            this.group = group;
            this.sql = sql;
            this.sortable = sortable;
            this.filterable = filterable;
            this.visible = visible;
        }

        static ColumnMeta visible(String key, String label, ColumnType type, String group, String sql,
                                  boolean sortable, boolean filterable) {
            return new ColumnMeta(key, label, type, group, sql, sortable, filterable, true);
        }

        static ColumnMeta hidden(String key, ColumnType type, String sql) {
            return new ColumnMeta(key, key, type, "Internal", sql, false, false, false);
        }

        String key() { return key; }
        String label() { return label; }
        ColumnType type() { return type; }
        String group() { return group; }
        String sql() { return sql; }
        boolean sortable() { return sortable; }
        boolean filterable() { return filterable; }
        boolean visible() { return visible; }
    }

    private static final class ExpressionEngine {
        private enum TokenType { NUMBER, IDENT, OPERATOR }
        private enum Assoc { LEFT, RIGHT }

        private record Token(TokenType type, String text, Double number) {}

        private record Operator(String symbol, int precedence, Assoc assoc, int arity) {}

        private static final Map<String, Operator> OPERATORS = Map.of(
                "+", new Operator("+", 1, Assoc.LEFT, 2),
                "-", new Operator("-", 1, Assoc.LEFT, 2),
                "*", new Operator("*", 2, Assoc.LEFT, 2),
                "/", new Operator("/", 2, Assoc.LEFT, 2),
                "NEG", new Operator("NEG", 3, Assoc.RIGHT, 1)
        );

        static CompileResult compile(String expression, Map<String, ColumnMeta> metaMap) {
            if (expression == null || expression.isBlank()) return null;
            List<Token> tokens = tokenize(expression);
            if (tokens.isEmpty()) return null;
            List<Token> output = new ArrayList<>();
            ArrayDeque<String> ops = new ArrayDeque<>();
            Token prev = null;
            Set<String> requiredKeys = new LinkedHashSet<>();

            for (Token token : tokens) {
                if (token.type == TokenType.NUMBER) {
                    output.add(token);
                    prev = token;
                } else if (token.type == TokenType.IDENT) {
                    ColumnMeta meta = metaMap.get(token.text);
                    if (meta == null || meta.type != ColumnType.NUMBER) {
                        return null;
                    }
                    requiredKeys.add(token.text);
                    output.add(token);
                    prev = token;
                } else if (token.type == TokenType.OPERATOR && "(".equals(token.text)) {
                    ops.push(token.text);
                    prev = token;
                } else if (token.type == TokenType.OPERATOR && ")".equals(token.text)) {
                    while (!ops.isEmpty() && !"(".equals(ops.peek())) {
                        output.add(new Token(TokenType.OPERATOR, ops.pop(), null));
                    }
                    if (ops.isEmpty() || !"(".equals(ops.pop())) return null;
                    prev = token;
                } else if (token.type == TokenType.OPERATOR) {
                    String op = token.text;
                    if ("-".equals(op) && (prev == null || (prev.type == TokenType.OPERATOR && !")".equals(prev.text)) || "(".equals(prev.text))) {
                        op = "NEG";
                    }
                    Operator o1 = OPERATORS.get(op);
                    if (o1 == null) return null;
                    while (!ops.isEmpty()) {
                        String top = ops.peek();
                        if ("(".equals(top)) break;
                        Operator o2 = OPERATORS.get(top);
                        if (o2 == null) break;
                        boolean higher = (o1.assoc == Assoc.LEFT && o1.precedence <= o2.precedence)
                                || (o1.assoc == Assoc.RIGHT && o1.precedence < o2.precedence);
                        if (!higher) break;
                        output.add(new Token(TokenType.OPERATOR, ops.pop(), null));
                    }
                    ops.push(op);
                    prev = new Token(TokenType.OPERATOR, op, null);
                }
            }
            while (!ops.isEmpty()) {
                String op = ops.pop();
                if ("(".equals(op) || ")".equals(op)) return null;
                output.add(new Token(TokenType.OPERATOR, op, null));
            }
            if (output.isEmpty()) return null;
            return new CompileResult(new CompiledExpression(output), requiredKeys);
        }

        static Double evaluate(CompiledExpression expression, Map<String, Object> row) {
            ArrayDeque<Double> stack = new ArrayDeque<>();
            for (Token token : expression.tokens) {
                if (token.type == TokenType.NUMBER) {
                    stack.push(token.number);
                } else if (token.type == TokenType.IDENT) {
                    Object val = row.get(token.text);
                    Double num = toNumber(val);
                    if (num == null) return null;
                    stack.push(num);
                } else if (token.type == TokenType.OPERATOR) {
                    Operator op = OPERATORS.get(token.text);
                    if (op == null) return null;
                    if (op.arity == 1) {
                        if (stack.isEmpty()) return null;
                        Double a = stack.pop();
                        stack.push(a != null ? -a : null);
                    } else {
                        if (stack.size() < 2) return null;
                        Double b = stack.pop();
                        Double a = stack.pop();
                        if (a == null || b == null) return null;
                        Double result = switch (op.symbol) {
                            case "+" -> a + b;
                            case "-" -> a - b;
                            case "*" -> a * b;
                            case "/" -> b == 0 ? null : a / b;
                            default -> null;
                        };
                        if (result == null) return null;
                        stack.push(result);
                    }
                }
            }
            return stack.isEmpty() ? null : stack.pop();
        }

        private static Double toNumber(Object value) {
            if (value == null) return null;
            if (value instanceof Number num) return num.doubleValue();
            try {
                return Double.parseDouble(value.toString());
            } catch (Exception e) {
                return null;
            }
        }

        private static List<Token> tokenize(String expr) {
            List<Token> tokens = new ArrayList<>();
            int i = 0;
            while (i < expr.length()) {
                char c = expr.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                    continue;
                }
                if (Character.isDigit(c) || c == '.') {
                    int start = i;
                    i++;
                    while (i < expr.length()) {
                        char n = expr.charAt(i);
                        if (Character.isDigit(n) || n == '.') {
                            i++;
                        } else {
                            break;
                        }
                    }
                    String text = expr.substring(start, i);
                    try {
                        tokens.add(new Token(TokenType.NUMBER, text, Double.parseDouble(text)));
                    } catch (NumberFormatException e) {
                        return List.of();
                    }
                    continue;
                }
                if (Character.isLetter(c) || c == '_') {
                    int start = i;
                    i++;
                    while (i < expr.length()) {
                        char n = expr.charAt(i);
                        if (Character.isLetterOrDigit(n) || n == '_') {
                            i++;
                        } else {
                            break;
                        }
                    }
                    String text = expr.substring(start, i);
                    tokens.add(new Token(TokenType.IDENT, text, null));
                    continue;
                }
                if ("+-*/()".indexOf(c) >= 0) {
                    tokens.add(new Token(TokenType.OPERATOR, String.valueOf(c), null));
                    i++;
                    continue;
                }
                return List.of();
            }
            return tokens;
        }

        private record CompileResult(CompiledExpression expression, Set<String> requiredKeys) {}

        private static final class CompiledExpression {
            private final List<Token> tokens;

            private CompiledExpression(List<Token> tokens) {
                this.tokens = tokens;
            }
        }
    }
}
