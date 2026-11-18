package com.echomind.saas.evaluation.golden;

import java.util.Map;
import java.util.Objects;

public record GoldenEvaluationBaseline(
        String datasetVersion,
        String datasetSha256,
        Map<String, GoldenEvaluationReport.RetrievalMetrics> retrievalMetrics,
        Map<String, GoldenEvaluationReport.HandoffMetrics> handoffMetrics
) {
    public GoldenEvaluationBaseline {
        datasetVersion = requireText(datasetVersion, "datasetVersion");
        datasetSha256 = requireText(datasetSha256, "datasetSha256");
        retrievalMetrics = Map.copyOf(Objects.requireNonNull(
                retrievalMetrics, "retrievalMetrics"));
        handoffMetrics = Map.copyOf(Objects.requireNonNull(
                handoffMetrics, "handoffMetrics"));
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
