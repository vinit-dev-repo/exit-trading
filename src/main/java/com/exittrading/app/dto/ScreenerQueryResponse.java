package com.exittrading.app.dto;

import java.util.List;
import java.util.Map;

public record ScreenerQueryResponse(
        String reportDate,
        List<ScreenerColumn> columns,
        List<ScreenerComputedColumn> computedColumns,
        List<Map<String, Object>> rows,
        long total,
        boolean hasMore,
        int offset,
        int limit
) {
}
