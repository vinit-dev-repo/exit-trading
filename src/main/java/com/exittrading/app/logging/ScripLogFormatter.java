package com.exittrading.app.logging;

import com.exittrading.app.dto.DepthView;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Produces a single consistent log line schema for scrip depth snapshots.
 * Intended for both holdings-derived depth and user-configured logging scrips.
 */
public final class ScripLogFormatter {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private ScripLogFormatter() {}

    public static String format(String instrument, String token, DepthView v) {
        return format(instrument, token, v, true);
    }

    public static String format(String instrument, String token, DepthView v, boolean includeLadders) {
        if (v == null) return "-";
        // Fill missing OHLC with best-effort defaults so logs stay populated
        if (v.getOpen() == null && v.getLtp() != null) v.setOpen(v.getLtp());
        if (v.getHigh() == null && v.getLtp() != null) v.setHigh(v.getLtp());
        if (v.getLow() == null && v.getLtp() != null) v.setLow(v.getLtp());

        String prefix = instrument != null && !instrument.isBlank()
                ? instrument
                : (v.getTradingsymbol() != null ? v.getTradingsymbol() : "-");
        String tok = token != null && !token.isBlank() ? token : "-";

        String ohlc = String.format("O=%s H=%s L=%s C=%s",
                fmt(v.getOpen()), fmt(v.getHigh()), fmt(v.getLow()), fmt(v.getPrevClose()));
        String ladders = String.format("bidQty=%d askQty=%d b1=%s a1=%s",
                v.getBuyQuantity(), v.getSellQuantity(), topLevel(v.getBuyLevels()), topLevel(v.getSellLevels()));
        String misc = String.format("vol=%s ltq=%s ltt=%s tick=%s",
                fmt(v.getVolume()), fmt(v.getLtq()), v.getLtt() != null ? v.getLtt() : "-",
                v.getTick() != null ? v.getTick() : "-");
        String captured = v.getCapturedAt() != null ? v.getCapturedAt().withZoneSameInstant(IST).toString() : "-";

        StringBuilder sb = new StringBuilder();
        sb.append(prefix)
                .append(" | token=").append(tok)
                .append(" | ltp=").append(fmt(v.getLtp()))
                .append(" | ").append(ohlc)
                .append(" | ").append(ladders)
                .append(" | ").append(misc);
        if (includeLadders) {
            sb.append(" | buyLadder=").append(ladder(v.getBuyLevels()))
                    .append(" | sellLadder=").append(ladder(v.getSellLevels()));
        }
        sb.append(" | captured=").append(captured);
        return sb.toString();
    }

    private static String fmt(Object value) {
        if (value == null) return "-";
        if (value instanceof BigDecimal bd) return bd.stripTrailingZeros().toPlainString();
        return value.toString();
    }

    private static String topLevel(List<DepthView.Level> levels) {
        if (levels == null || levels.isEmpty()) return "-";
        DepthView.Level lvl = levels.get(0);
        return lvl.getPrice() + "/" + lvl.getQuantity() + "(" + lvl.getOrders() + ")";
    }

    private static String ladder(List<DepthView.Level> levels) {
        try {
            if (levels == null || levels.isEmpty()) return "-";
            return levels.stream()
                    .limit(5)
                    .map(lv -> String.format("%.4f/%d(%d)", lv.getPrice(), lv.getQuantity(), lv.getOrders()))
                    .collect(Collectors.joining("|"));
        } catch (Exception e) {
            return "-";
        }
    }
}
