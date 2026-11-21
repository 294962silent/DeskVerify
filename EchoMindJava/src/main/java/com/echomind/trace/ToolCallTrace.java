package com.echomind.trace;

import com.fasterxml.jackson.annotation.JsonInclude;

public record ToolCallTrace(
        String toolName,
        boolean success,
        boolean fallbackUsed,
        boolean cached,
        boolean reranked,
        long latencyMs,
        String error,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer vectorEvidenceHits
) {
    public ToolCallTrace(
            String toolName, boolean success, boolean fallbackUsed, boolean cached,
            boolean reranked, long latencyMs, String error) {
        this(toolName, success, fallbackUsed, cached, reranked, latencyMs, error, null);
    }
}
