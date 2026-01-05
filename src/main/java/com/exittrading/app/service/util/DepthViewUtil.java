package com.exittrading.app.service.util;

import com.exittrading.app.dto.DepthView;
import java.math.BigDecimal;
import java.util.List;

public final class DepthViewUtil {
    private DepthViewUtil() {}

    public static boolean needsEnrichment(DepthView view) {
        if (view == null) return true;
        if (isMissing(view.getLtp())
                || isMissing(view.getOpen())
                || isMissing(view.getPrevClose())
                || isMissing(view.getLowerCircuit())
                || isMissing(view.getUpperCircuit())) {
            return true;
        }
        if (isEmptyLevels(view.getBuyLevels()) || isEmptyLevels(view.getSellLevels())) return true;
        return false;
    }

    public static DepthView mergeMissing(DepthView primary, DepthView fallback) {
        if (primary == null) return fallback;
        if (fallback == null) return primary;

        if (isMissing(primary.getLtp()) && !isMissing(fallback.getLtp())) {
            primary.setLtp(fallback.getLtp());
        }
        if (isMissing(primary.getOpen()) && !isMissing(fallback.getOpen())) {
            primary.setOpen(fallback.getOpen());
        }
        if (isMissing(primary.getHigh()) && !isMissing(fallback.getHigh())) {
            primary.setHigh(fallback.getHigh());
        }
        if (isMissing(primary.getLow()) && !isMissing(fallback.getLow())) {
            primary.setLow(fallback.getLow());
        }
        if (isMissing(primary.getPrevClose()) && !isMissing(fallback.getPrevClose())) {
            primary.setPrevClose(fallback.getPrevClose());
        }
        if (isMissing(primary.getAvgPrice()) && !isMissing(fallback.getAvgPrice())) {
            primary.setAvgPrice(fallback.getAvgPrice());
        }
        if (isMissing(primary.getLowerCircuit()) && !isMissing(fallback.getLowerCircuit())) {
            primary.setLowerCircuit(fallback.getLowerCircuit());
        }
        if (isMissing(primary.getUpperCircuit()) && !isMissing(fallback.getUpperCircuit())) {
            primary.setUpperCircuit(fallback.getUpperCircuit());
        }
        if (isMissing(primary.getTick()) && !isMissing(fallback.getTick())) {
            primary.setTick(fallback.getTick());
        }
        if (isMissingLong(primary.getVolume()) && !isMissingLong(fallback.getVolume())) {
            primary.setVolume(fallback.getVolume());
        }
        if (isMissingLong(primary.getLtq()) && !isMissingLong(fallback.getLtq())) {
            primary.setLtq(fallback.getLtq());
        }
        if ((primary.getLtt() == null || primary.getLtt().isBlank())
                && fallback.getLtt() != null
                && !fallback.getLtt().isBlank()) {
            primary.setLtt(fallback.getLtt());
        }

        boolean filledBuys = false;
        if (isEmptyLevels(primary.getBuyLevels()) && !isEmptyLevels(fallback.getBuyLevels())) {
            primary.setBuyLevels(fallback.getBuyLevels());
            filledBuys = true;
        }
        boolean filledSells = false;
        if (isEmptyLevels(primary.getSellLevels()) && !isEmptyLevels(fallback.getSellLevels())) {
            primary.setSellLevels(fallback.getSellLevels());
            filledSells = true;
        }
        if (filledBuys && primary.getBuyQuantity() == 0) {
            primary.setBuyQuantity(sumQty(primary.getBuyLevels()));
        }
        if (filledSells && primary.getSellQuantity() == 0) {
            primary.setSellQuantity(sumQty(primary.getSellLevels()));
        }

        return primary;
    }

    private static boolean isMissing(BigDecimal value) {
        return value == null || value.doubleValue() <= 0.0;
    }

    private static boolean isMissing(Double value) {
        return value == null || value.isNaN() || value <= 0.0;
    }

    private static boolean isMissingLong(Long value) {
        return value == null || value <= 0L;
    }

    private static boolean isEmptyLevels(List<DepthView.Level> levels) {
        return !hasNonZeroLevels(levels);
    }

    private static boolean hasNonZeroLevels(List<DepthView.Level> levels) {
        if (levels == null || levels.isEmpty()) return false;
        for (DepthView.Level lvl : levels) {
            if (lvl == null) continue;
            if (lvl.getPrice() > 0.0 || lvl.getQuantity() > 0 || lvl.getOrders() > 0) {
                return true;
            }
        }
        return false;
    }

    private static long sumQty(List<DepthView.Level> levels) {
        if (levels == null || levels.isEmpty()) return 0L;
        long sum = 0L;
        for (DepthView.Level lvl : levels) {
            if (lvl == null) continue;
            sum += Math.max(0, lvl.getQuantity());
        }
        return sum;
    }
}
