package com.exittrading.app.dto;

import java.util.List;

public record EsmResult(String downloadPath, java.util.List<ScripAnalysis> analysis, String message) {
    public EsmResult(String downloadPath, java.util.List<ScripAnalysis> analysis) {
        this(downloadPath, analysis, null);
    }
}
