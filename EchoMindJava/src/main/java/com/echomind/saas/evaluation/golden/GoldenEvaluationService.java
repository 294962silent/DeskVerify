package com.echomind.saas.evaluation.golden;

import com.echomind.saas.evaluation.golden.GoldenDatasetLoader.LoadedDataset;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public final class GoldenEvaluationService {

    private static final List<String> LIMITATIONS = List.of(
            "Synthetic dataset; no human review or statistical representativeness claim.",
            "Policy-level handoff evaluation; no external LLM calls.",
            "Small-scale method validation only; not a production quality benchmark.");

    private final GoldenDatasetLoader loader;
    private final GoldenKnowledgeImporter importer;
    private final GoldenRagEvaluator ragEvaluator;
    private final HandoffEvaluator handoffEvaluator;
    private final GoldenEvaluationReportWriter reportWriter;

    public GoldenEvaluationService(
            GoldenDatasetLoader loader,
            GoldenKnowledgeImporter importer,
            GoldenRagEvaluator ragEvaluator,
            HandoffEvaluator handoffEvaluator,
            GoldenEvaluationReportWriter reportWriter) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.importer = Objects.requireNonNull(importer, "importer");
        this.ragEvaluator = Objects.requireNonNull(ragEvaluator, "ragEvaluator");
        this.handoffEvaluator = Objects.requireNonNull(
                handoffEvaluator, "handoffEvaluator");
        this.reportWriter = Objects.requireNonNull(reportWriter, "reportWriter");
    }

    public GoldenEvaluationReport run(String datasetVersion, String jobId) {
        LoadedDataset loaded = loader.loadWithManifest(datasetVersion);
        GoldenDataset dataset = loaded.dataset();
        importer.ensureImported(dataset);
        GoldenRagEvaluator.Outcome retrieval = ragEvaluator.evaluate(dataset);
        HandoffEvaluator.Outcome handoff = handoffEvaluator.evaluate(
                dataset.handoffCases());
        GoldenEvaluationReport report = new GoldenEvaluationReport(
                jobId,
                dataset.datasetVersion(),
                loaded.manifest().datasetFileSha256(),
                codeSha(),
                loaded.manifest().embeddingModel(),
                dataset.provenance(),
                Instant.now().toString(),
                new GoldenEvaluationReport.Totals(
                        dataset.documents().size(),
                        dataset.documents().stream()
                                .mapToInt(document -> document.expectedChunks().size())
                                .sum(),
                        dataset.retrievalCases().size(),
                        countSplit(dataset.retrievalCases(), GoldenDataset.Split.DEV),
                        countSplit(dataset.retrievalCases(), GoldenDataset.Split.HOLDOUT),
                        dataset.handoffCases().size(),
                        countSplit(dataset.handoffCases(), GoldenDataset.Split.DEV),
                        countSplit(dataset.handoffCases(), GoldenDataset.Split.HOLDOUT),
                        dataset.provenance().externalLlmTokens()),
                retrieval.metrics(),
                handoff.metrics(),
                handoff.reasonMetrics(),
                retrieval.cases(),
                handoff.cases(),
                LIMITATIONS,
                null,
                null);
        GoldenEvaluationReportWriter.WrittenPaths paths = reportWriter.write(report);
        return report.withReportPaths(paths.jsonPath(), paths.markdownPath());
    }

    private static <T> int countSplit(
            List<T> values,
            GoldenDataset.Split split) {
        return (int) values.stream()
                .filter(value -> splitOf(value) == split)
                .count();
    }

    private static GoldenDataset.Split splitOf(Object value) {
        if (value instanceof GoldenDataset.RetrievalCase retrievalCase) {
            return retrievalCase.split();
        }
        return ((GoldenDataset.HandoffCase) value).split();
    }

    private static String codeSha() {
        String ciSha = System.getenv("GITHUB_SHA");
        if (ciSha != null && !ciSha.isBlank()) {
            return ciSha;
        }
        String localSha = System.getenv("ECHOMIND_CODE_SHA");
        return localSha == null || localSha.isBlank() ? "working-tree" : localSha;
    }
}
