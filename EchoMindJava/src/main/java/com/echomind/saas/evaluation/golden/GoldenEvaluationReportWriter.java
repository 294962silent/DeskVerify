package com.echomind.saas.evaluation.golden;

import com.echomind.config.EchoMindProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class GoldenEvaluationReportWriter {

    private final ObjectMapper objectMapper;
    private final EchoMindProperties properties;

    public GoldenEvaluationReportWriter(
            ObjectMapper objectMapper,
            EchoMindProperties properties) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public WrittenPaths write(GoldenEvaluationReport report) {
        Path jobsPath = Path.of(properties.getEval().getJobsPath()).toAbsolutePath();
        Path directory = jobsPath.getParent().resolve("reports");
        String prefix = report.jobId() + "-" + report.datasetVersion();
        Path jsonPath = directory.resolve(prefix + ".json");
        Path markdownPath = directory.resolve(prefix + ".md");
        try {
            Files.createDirectories(directory);
            writeAtomic(jsonPath, writeJson(report));
            writeAtomic(markdownPath, writeMarkdown(report));
            return new WrittenPaths(jsonPath.toString(), markdownPath.toString());
        } catch (IOException exception) {
            throw new GoldenDatasetException(
                    "GOLDEN_REPORT_WRITE_FAILED",
                    "failed to persist golden evaluation report",
                    exception);
        }
    }

    private String writeJson(GoldenEvaluationReport report) throws IOException {
        return objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(report);
    }

    private static String writeMarkdown(GoldenEvaluationReport report) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# Golden RAG And Handoff Evaluation\n\n");
        markdown.append("- Dataset: `").append(report.datasetVersion()).append("`\n");
        markdown.append("- Dataset SHA-256: `").append(report.datasetSha256()).append("`\n");
        markdown.append("- Code SHA: `").append(report.codeSha()).append("`\n");
        markdown.append("- Embedding model: `").append(report.embeddingModel()).append("`\n");
        markdown.append("- Generated at: `").append(report.generatedAt()).append("`\n");
        markdown.append("- External LLM tokens: `")
                .append(report.totals().externalLlmTokens())
                .append("`\n");
        markdown.append("- Provenance: `").append(report.provenance().kind()).append("`\n");
        markdown.append("- Human reviewed: `")
                .append(report.provenance().humanReviewed()).append("`\n");
        markdown.append("- Statistically representative: `")
                .append(report.provenance().statisticallyRepresentative()).append("`\n\n");

        appendReleaseGate(markdown, report.gate());

        markdown.append("## Retrieval Metrics\n\n");
        markdown.append("| Split | Recall@1 | Recall@3 | Recall@5 | MRR@5 | "
                + "Citation hit | ACL violations | Degraded | P95 ms |\n");
        markdown.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        appendRetrievalMetrics(markdown, report.retrievalMetrics());

        markdown.append("\n## Handoff Policy Metrics\n\n");
        markdown.append("| Split | Accuracy | Precision | Recall | False escalation | "
                + "Missed handoff | TP | FP | TN | FN |\n");
        markdown.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        appendHandoffMetrics(markdown, report.handoffMetrics());

        markdown.append("\n## Failed Cases\n\n");
        long failedRetrieval = report.retrievalCases().stream()
                .filter(result -> !result.passed())
                .count();
        long failedHandoff = report.handoffCases().stream()
                .filter(result -> !result.passed())
                .count();
        markdown.append("- Retrieval: ").append(failedRetrieval).append("\n");
        markdown.append("- Handoff: ").append(failedHandoff).append("\n");
        appendFailedCases(markdown, report);

        markdown.append("\n## Limitations\n\n");
        report.limitations().forEach(limitation ->
                markdown.append("- ").append(limitation).append("\n"));
        return markdown.toString();
    }

    private static void appendReleaseGate(
            StringBuilder markdown,
            GoldenEvaluationGate gate) {
        markdown.append("## Release Gate\n\n");
        markdown.append("- Status: `")
                .append(gate.passed() ? "PASSED" : "FAILED")
                .append("`\n");
        markdown.append("- Blocking failures: `")
                .append(gate.blockingFailureCount())
                .append("`\n");
        markdown.append("- Advisory warnings: `")
                .append(gate.advisoryWarningCount())
                .append("`\n\n");
        markdown.append("| Check | Split | Blocking | Result | Expected | Observed |\n");
        markdown.append("| --- | --- | --- | --- | --- | --- |\n");
        for (GoldenEvaluationGate.GateCheck check : gate.checks()) {
            markdown.append("| ").append(check.id())
                    .append(" | ").append(check.split())
                    .append(" | ").append(check.blocking())
                    .append(" | ").append(check.passed() ? "PASS" : "FAIL")
                    .append(" | ").append(check.expectation())
                    .append(" | ").append(check.observed())
                    .append(" |\n");
        }
        markdown.append("\n");
    }

    private static void appendRetrievalMetrics(
            StringBuilder markdown,
            Map<String, GoldenEvaluationReport.RetrievalMetrics> metrics) {
        for (String split : new String[]{"DEV", "HOLDOUT", "ALL"}) {
            GoldenEvaluationReport.RetrievalMetrics value = metrics.get(split);
            markdown.append("| ").append(split)
                    .append(" | ").append(value.recallAt1())
                    .append(" | ").append(value.recallAt3())
                    .append(" | ").append(value.recallAt5())
                    .append(" | ").append(value.mrrAt5())
                    .append(" | ").append(value.citationHitRate())
                    .append(" | ").append(value.aclViolations())
                    .append(" | ").append(value.degradedRate())
                    .append(" | ").append(value.p95LatencyMs())
                    .append(" |\n");
        }
    }

    private static void appendHandoffMetrics(
            StringBuilder markdown,
            Map<String, GoldenEvaluationReport.HandoffMetrics> metrics) {
        for (String split : new String[]{"DEV", "HOLDOUT", "ALL"}) {
            GoldenEvaluationReport.HandoffMetrics value = metrics.get(split);
            markdown.append("| ").append(split)
                    .append(" | ").append(value.accuracy())
                    .append(" | ").append(value.precision())
                    .append(" | ").append(value.recall())
                    .append(" | ").append(value.falseEscalationRate())
                    .append(" | ").append(value.missedHandoffRate())
                    .append(" | ").append(value.truePositive())
                    .append(" | ").append(value.falsePositive())
                    .append(" | ").append(value.trueNegative())
                    .append(" | ").append(value.falseNegative())
                    .append(" |\n");
        }
    }

    private static void appendFailedCases(
            StringBuilder markdown,
            GoldenEvaluationReport report) {
        if (report.retrievalCases().stream().noneMatch(result -> !result.passed())
                && report.handoffCases().stream().noneMatch(result -> !result.passed())) {
            markdown.append("- None\n");
            return;
        }
        for (GoldenEvaluationReport.RetrievalCaseResult result
                : report.retrievalCases()) {
            if (!result.passed()) {
                markdown.append("- `").append(result.caseId())
                        .append("` expected `").append(result.expectedChunkId())
                        .append("`, rank=").append(result.expectedRank())
                        .append(", degraded=").append(result.degraded())
                        .append(", aclViolation=").append(result.aclViolation())
                        .append("\n");
            }
        }
        for (GoldenEvaluationReport.HandoffCaseResult result
                : report.handoffCases()) {
            if (!result.passed()) {
                markdown.append("- `").append(result.caseId())
                        .append("` expected ").append(result.expectedHandoff())
                        .append("/").append(result.expectedReason())
                        .append(", actual ").append(result.actualHandoff())
                        .append("/").append(result.actualReason())
                        .append("\n");
            }
        }
    }

    private static void writeAtomic(Path path, String content) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, content, StandardCharsets.UTF_8);
        try {
            Files.move(
                    temporary,
                    path,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record WrittenPaths(String jsonPath, String markdownPath) {
    }
}
