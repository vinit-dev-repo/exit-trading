package com.exittrading.app.dto;

import java.util.List;

public record ScreenerQueryRequest(
        String reportDate,
        List<String> columns,
        List<ScreenerComputedColumn> computedColumns,
        ScreenerFilter filters,
        ScreenerSort sort,
        Integer limit,
        Integer offset
) {
}
