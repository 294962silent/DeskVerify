package com.echomind.saas.evaluation.golden;

import static org.assertj.core.api.Assertions.assertThat;

import com.echomind.config.EchoMindProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GoldenEvaluationReportWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writesJsonAndMarkdownWithProvenanceAndZeroTokenEvidence() throws Exception {
        EchoMindProperties properties = new EchoMindProperties();
        properties.getEval().setJobsPath(tempDir.resolve("jobs.json").toString());
        ObjectMapper objectMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        GoldenEvaluationReportWriter writer =
                new GoldenEvaluationReportWriter(objectMapper, properties);

        GoldenEvaluationReportWriter.WrittenPaths paths =
                writer.write(report());

        assertThat(Files.readString(Path.of(paths.jsonPath())))
                .contains("\"dataset_version\" : \"rag-support-golden-v1\"")
                .contains("\"external_llm_tokens\" : 0");
        assertThat(Files.readString(Path.of(paths.markdownPath())))
                .contains("Dataset: `rag-support-golden-v1`")
                .contains("External LLM tokens: `0`")
                .contains("Synthetic dataset");
    }

    private static GoldenEvaluationReport report() {
        GoldenEvaluationReport.RetrievalMetrics retrieval =
                new GoldenEvaluationReport.RetrievalMetrics(
                        1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 10, 20);
        GoldenEvaluationReport.HandoffMetrics handoff =
                new GoldenEvaluationReport.HandoffMetrics(
                        1, 1, 1, 0, 0, 10, 0, 10, 0);
        return new GoldenEvaluationReport(
                "job-1",
                "rag-support-golden-v1",
                "d394771cd02c81d230225979f7015c1ef6b4e2f850ddfefb9ef70e59926cd968",
                "working-tree",
                "bge-small-zh-v1.5-onnx-fp32",
                new GoldenDataset.Provenance("synthetic", false, false, 0),
                "2026-11-18T00:00:00Z",
                new GoldenEvaluationReport.Totals(12, 12, 30, 20, 10, 20, 12, 8, 0),
                Map.of("DEV", retrieval, "HOLDOUT", retrieval, "ALL", retrieval),
                Map.of("DEV", handoff, "HOLDOUT", handoff, "ALL", handoff),
                Map.of(),
                List.of(),
                List.of(),
                List.of(
                        "Synthetic dataset; no human review or statistical representativeness claim.",
                        "Policy-level handoff evaluation; no external LLM calls."),
                null,
                null);
    }
}
