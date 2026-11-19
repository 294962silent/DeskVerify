package com.echomind.saas.evaluation.golden;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record GoldenEvaluationReport(
        String jobId,
        String datasetVersion,
        String datasetSha256,
        String codeSha,
        String embeddingModel,
        GoldenDataset.Provenance provenance,
        String generatedAt,
        Totals totals,
        GoldenEvaluationGate gate,
        Map<String, RetrievalMetrics> retrievalMetrics,
        Map<String, HandoffMetrics> handoffMetrics,
        Map<String, Map<String, ReasonMetrics>> handoffReasonMetrics,
        List<RetrievalCaseResult> retrievalCases,
        List<HandoffCaseResult> handoffCases,
        List<String> limitations,
        String jsonPath,
        String markdownPath
) {
    public GoldenEvaluationReport {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(datasetVersion, "datasetVersion");
        Objects.requireNonNull(datasetSha256, "datasetSha256");
        Objects.requireNonNull(codeSha, "codeSha");
        Objects.requireNonNull(embeddingModel, "embeddingModel");
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(generatedAt, "generatedAt");
        Objects.requireNonNull(totals, "totals");
        Objects.requireNonNull(gate, "gate");
        retrievalMetrics = Map.copyOf(Objects.requireNonNull(
                retrievalMetrics, "retrievalMetrics"));
        handoffMetrics = Map.copyOf(Objects.requireNonNull(
                handoffMetrics, "handoffMetrics"));
        handoffReasonMetrics = Map.copyOf(Objects.requireNonNull(
                handoffReasonMetrics, "handoffReasonMetrics"));
        retrievalCases = List.copyOf(Objects.requireNonNull(
                retrievalCases, "retrievalCases"));
        handoffCases = List.copyOf(Objects.requireNonNull(
                handoffCases, "handoffCases"));
        limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations"));
    }

    public GoldenEvaluationReport withReportPaths(String json, String markdown) {
        return new GoldenEvaluationReport(
                jobId,
                datasetVersion,
                datasetSha256,
                codeSha,
                embeddingModel,
                provenance,
                generatedAt,
                totals,
                gate,
                retrievalMetrics,
                handoffMetrics,
                handoffReasonMetrics,
                retrievalCases,
                handoffCases,
                limitations,
                json,
                markdown);
    }

    public record Totals(
            int documentCount,
            int chunkCount,
            int retrievalCases,
            int retrievalDev,
            int retrievalHoldout,
            int handoffCases,
            int handoffDev,
            int handoffHoldout,
            int externalLlmTokens
    ) {
    }

    public record RetrievalMetrics(
            double recallAt1,
            double recallAt3,
            double recallAt5,
            double mrrAt5,
            double documentHitRate,
            double versionHitRate,
            double chunkHitRate,
            double citationHitRate,
            int aclViolations,
            double degradedRate,
            long p50LatencyMs,
            long p95LatencyMs
    ) {
    }

    public record HandoffMetrics(
            double accuracy,
            double precision,
            double recall,
            double falseEscalationRate,
            double missedHandoffRate,
            int truePositive,
            int falsePositive,
            int trueNegative,
            int falseNegative
    ) {
    }

    public record ReasonMetrics(int total, int correct) {
    }

    public record RetrievalCaseResult(
            String caseId,
            GoldenDataset.Split split,
            String query,
            String expectedDocumentId,
            int expectedVersionNumber,
            String expectedChunkId,
            String topChunkId,
            int expectedRank,
            double reciprocalRank,
            boolean documentHit,
            boolean versionHit,
            boolean chunkHit,
            boolean citationHit,
            boolean aclViolation,
            boolean degraded,
            boolean passed,
            List<String> degradedModes,
            long latencyMs
    ) {
        public RetrievalCaseResult {
            degradedModes = List.copyOf(Objects.requireNonNull(
                    degradedModes, "degradedModes"));
        }
    }

    public record HandoffCaseResult(
            String caseId,
            GoldenDataset.Split split,
            String message,
            boolean expectedHandoff,
            boolean actualHandoff,
            String expectedReason,
            String actualReason,
            boolean passed
    ) {
    }
}
