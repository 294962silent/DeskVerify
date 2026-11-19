package com.echomind.saas.evaluation.golden;

import java.util.Map;
import java.util.Objects;

public record GoldenManifest(
        String datasetVersion,
        int schemaVersion,
        String createdOn,
        String embeddingModel,
        String datasetFileSha256,
        int documentCount,
        int chunkCount,
        int retrievalCaseCount,
        int handoffCaseCount,
        Map<String, Integer> retrievalSplitCounts,
        Map<String, Integer> handoffSplitCounts,
        GoldenDataset.Provenance provenance
) {
    public GoldenManifest {
        datasetVersion = requireText(datasetVersion, "datasetVersion");
        createdOn = requireText(createdOn, "createdOn");
        embeddingModel = requireText(embeddingModel, "embeddingModel");
        datasetFileSha256 = requireText(datasetFileSha256, "datasetFileSha256");
        provenance = Objects.requireNonNull(provenance, "provenance");
        retrievalSplitCounts = Map.copyOf(Objects.requireNonNull(
                retrievalSplitCounts, "retrievalSplitCounts"));
        handoffSplitCounts = Map.copyOf(Objects.requireNonNull(
                handoffSplitCounts, "handoffSplitCounts"));
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
