package com.echomind.saas.evaluation.golden;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GoldenEvaluationServiceTest {

    @Test
    void assemblesOneReportFromDatasetRetrievalAndHandoffOutcomes() {
        GoldenDatasetLoader loader = mock(GoldenDatasetLoader.class);
        GoldenKnowledgeImporter importer = mock(GoldenKnowledgeImporter.class);
        GoldenRagEvaluator ragEvaluator = mock(GoldenRagEvaluator.class);
        HandoffEvaluator handoffEvaluator = mock(HandoffEvaluator.class);
        GoldenEvaluationGateEvaluator gateEvaluator =
                mock(GoldenEvaluationGateEvaluator.class);
        GoldenEvaluationReportWriter writer =
                mock(GoldenEvaluationReportWriter.class);
        GoldenDataset dataset = fixtureDataset();
        GoldenManifest manifest = new GoldenManifest(
                dataset.datasetVersion(),
                1,
                "2026-11-18",
                "bge-small-zh-v1.5-onnx-fp32",
                "a".repeat(64),
                1,
                1,
                1,
                1,
                Map.of("DEV", 1, "HOLDOUT", 0),
                Map.of("DEV", 1, "HOLDOUT", 0),
                dataset.provenance());
        GoldenEvaluationReport.RetrievalMetrics retrievalMetrics =
                new GoldenEvaluationReport.RetrievalMetrics(
                        1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 1, 1);
        GoldenEvaluationReport.HandoffMetrics handoffMetrics =
                new GoldenEvaluationReport.HandoffMetrics(
                        1, 1, 1, 0, 0, 1, 0, 0, 0);
        when(loader.loadWithManifest("rag-support-golden-v1"))
                .thenReturn(new GoldenDatasetLoader.LoadedDataset(dataset, manifest));
        when(ragEvaluator.evaluate(dataset)).thenReturn(
                new GoldenRagEvaluator.Outcome(
                        Map.of("DEV", retrievalMetrics,
                                "HOLDOUT", retrievalMetrics,
                                "ALL", retrievalMetrics),
                        List.of()));
        when(handoffEvaluator.evaluate(dataset.handoffCases())).thenReturn(
                new HandoffEvaluator.Outcome(
                        Map.of("DEV", handoffMetrics,
                                "HOLDOUT", handoffMetrics,
                                "ALL", handoffMetrics),
                        List.of(),
                        Map.of()));
        GoldenEvaluationGate gate = gate();
        when(gateEvaluator.evaluate(
                "rag-support-golden-v1",
                "a".repeat(64),
                Map.of("DEV", retrievalMetrics,
                        "HOLDOUT", retrievalMetrics,
                        "ALL", retrievalMetrics),
                Map.of("DEV", handoffMetrics,
                        "HOLDOUT", handoffMetrics,
                        "ALL", handoffMetrics)))
                .thenReturn(gate);
        when(writer.write(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new GoldenEvaluationReportWriter.WrittenPaths(
                        "report.json", "report.md"));
        GoldenEvaluationService service = new GoldenEvaluationService(
                loader, importer, ragEvaluator, handoffEvaluator,
                gateEvaluator, writer);

        GoldenEvaluationReport report = service.run(
                "rag-support-golden-v1", "job-1");

        assertThat(report.datasetSha256()).isEqualTo("a".repeat(64));
        assertThat(report.jsonPath()).isEqualTo("report.json");
        assertThat(report.markdownPath()).isEqualTo("report.md");
        assertThat(report.gate()).isSameAs(gate);
        assertThat(report.totals().externalLlmTokens()).isZero();
        verify(importer).ensureImported(dataset);
    }

    private static GoldenEvaluationGate gate() {
        return new GoldenEvaluationGate(
                true,
                List.of(new GoldenEvaluationGate.GateCheck(
                        "retrieval.DEV.recall_at5",
                        "DEV",
                        true,
                        true,
                        ">= 0.9800",
                        "1.0000")),
                new GoldenEvaluationGate.Thresholds(
                        0.02, 0, 0.0, 0.95, 0.10, 0.10, 2000));
    }

    private static GoldenDataset fixtureDataset() {
        GoldenDataset.Provenance provenance =
                new GoldenDataset.Provenance("synthetic", false, false, 0);
        GoldenDataset.Document document = new GoldenDataset.Document(
                "doc-1",
                "Policy",
                1,
                com.echomind.saas.rag.domain.KnowledgeVisibility.TENANT,
                null,
                "b".repeat(64),
                "# Policy\n\nContent",
                List.of(new GoldenDataset.ChunkExpectation(
                        0, "chunk-1", "b".repeat(64), "Policy", "Policy")));
        GoldenDataset.RetrievalCase retrievalCase = new GoldenDataset.RetrievalCase(
                "rag-1",
                GoldenDataset.Split.DEV,
                "query",
                GoldenDataset.ActorScope.TENANT_ADMIN,
                "doc-1",
                1,
                "chunk-1",
                "Policy",
                true);
        GoldenDataset.HandoffCase handoffCase = new GoldenDataset.HandoffCase(
                "handoff-1",
                GoldenDataset.Split.DEV,
                "question",
                new GoldenDataset.HandoffSignals(
                        false, false, false, false, false, true),
                false,
                "KNOWLEDGE_SUFFICIENT");
        return new GoldenDataset(
                "rag-support-golden-v1",
                1,
                "tenant-golden-v1",
                provenance,
                List.of(document),
                List.of(retrievalCase),
                List.of(handoffCase));
    }
}
