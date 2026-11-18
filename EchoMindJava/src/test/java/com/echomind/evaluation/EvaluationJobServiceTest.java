package com.echomind.evaluation;

import com.echomind.api.dto.EvalRunRequest;
import com.echomind.config.EchoMindProperties;
import com.echomind.saas.api.error.ResourceNotFoundException;
import com.echomind.saas.evaluation.domain.EvaluationJob;
import com.echomind.saas.evaluation.golden.GoldenDataset;
import com.echomind.saas.evaluation.golden.GoldenDatasetException;
import com.echomind.saas.evaluation.golden.GoldenEvaluationReport;
import com.echomind.saas.evaluation.golden.GoldenEvaluationService;
import com.echomind.saas.evaluation.golden.GoldenEvaluationGate;
import com.echomind.saas.identity.domain.ActorRole;
import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.identity.domain.TenantScopedActorKey;
import com.echomind.saas.identity.domain.UserId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.awaitility.Awaitility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvaluationJobServiceTest {

    private static final EvalRunRequest REQUEST = new EvalRunRequest(List.of(), List.of());

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private EndToEndEvaluator evaluator;
    private GoldenEvaluationService goldenEvaluationService;
    private EchoMindProperties properties;
    private EvaluationJobService service;

    @BeforeEach
    void setUp() {
        evaluator = mock(EndToEndEvaluator.class);
        goldenEvaluationService = mock(GoldenEvaluationService.class);
        when(evaluator.run(any(), any())).thenReturn(Map.of("ok", true));
        properties = new EchoMindProperties();
        properties.getEval().setJobsPath(tempDir.resolve("jobs.json").toString());
        service = new EvaluationJobService(
                evaluator, goldenEvaluationService, objectMapper, properties);
        service.load();
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    @Test
    void asyncExecutionUsesTenantScopedKeyWhileOwnershipUsesRawActorFields() {
        ActorScope owner = actor("tenant-a", "user-1");

        EvaluationJob submitted = service.submit(REQUEST, owner);

        verify(evaluator, timeout(2000)).run(
                any(EvalRunRequest.class),
                eq(new TenantScopedActorKey(
                        new TenantId("tenant-a"), new UserId("user-1"))));
        EvaluationJob loaded = service.get(submitted.jobId(), owner);
        assertThat(loaded.tenantId()).isEqualTo(new TenantId("tenant-a"));
        assertThat(loaded.ownerUserId()).isEqualTo(new UserId("user-1"));
        assertThatThrownBy(() -> service.get(submitted.jobId(), actor("tenant-a", "user-2")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void retentionIsIndependentPerTenantAndOwner() {
        ActorScope firstOwner = actor("tenant-a", "user-1");
        ActorScope secondOwner = actor("tenant-b", "user-1");
        List<String> firstOwnerJobIds = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            firstOwnerJobIds.add(service.submit(REQUEST, firstOwner).jobId());
        }
        EvaluationJob otherOwnerJob = service.submit(REQUEST, secondOwner);

        assertThat(service.list(firstOwner, 100)).hasSize(20);
        assertThat(service.list(secondOwner, 100))
                .extracting(EvaluationJob::jobId)
                .containsExactly(otherOwnerJob.jobId());
        assertThatThrownBy(() -> service.get(firstOwnerJobIds.get(0), firstOwner))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(service.get(firstOwnerJobIds.get(20), firstOwner).jobId())
                .isEqualTo(firstOwnerJobIds.get(20));
    }

    @Test
    void loadDiscardsLegacyJobsWithoutOwnershipBeforeRetention() throws Exception {
        ActorScope owner = actor("tenant-a", "user-1");
        List<Map<String, Object>> storedJobs = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            storedJobs.add(job("legacy-" + i, null, null));
        }
        storedJobs.add(job("owned-job", "tenant-a", "user-1"));
        objectMapper.writeValue(propertiesPath().toFile(), Map.of("jobs", storedJobs));

        service.load();

        assertThat(service.list(owner, 100))
                .extracting(EvaluationJob::jobId)
                .containsExactly("owned-job");
        assertThatThrownBy(() -> service.get("legacy-0", owner))
                .isInstanceOf(ResourceNotFoundException.class);
        service.close();
        JsonNode persisted = objectMapper.readTree(propertiesPath().toFile());
        assertThat(persisted.get("jobs")).hasSize(1);
        assertThat(persisted.get("jobs").get(0).get("job_id").asText()).isEqualTo("owned-job");
    }

    @Test
    void goldenRagJobPersistsReportAndProtectsMarkdownOwnership() throws Exception {
        ActorScope owner = actor("tenant-a", "user-1");
        Path markdown = tempDir.resolve("report.md");
        Files.writeString(markdown, "# Golden report\n");
        when(goldenEvaluationService.run(
                eq("rag-support-golden-v1"), anyString()))
                .thenReturn(goldenReport(markdown.toString()));

        EvaluationJob submitted = service.submitGoldenRag(
                "rag-support-golden-v1", owner);

        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            EvaluationJob loaded = service.get(submitted.jobId(), owner);
            assertThat(loaded.status()).isEqualTo("succeeded");
            assertThat(loaded.report())
                    .containsEntry("dataset_version", "rag-support-golden-v1")
                    .containsEntry(
                            "report_download_path",
                            "/eval/jobs/" + submitted.jobId() + "/report.md");
            assertThat(loaded.report()).doesNotContainKeys(
                    "json_path", "markdown_path");
        });
        assertThat(service.markdownReport(submitted.jobId(), owner))
                .isEqualTo("# Golden report\n");
        assertThatThrownBy(() -> service.markdownReport(
                submitted.jobId(), actor("tenant-a", "user-2")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void goldenRagFailureUsesStableErrorCodeWithoutLocalDetails() {
        ActorScope owner = actor("tenant-a", "user-1");
        when(goldenEvaluationService.run(
                eq("missing-dataset"), anyString()))
                .thenThrow(new GoldenDatasetException(
                        "GOLDEN_DATASET_NOT_FOUND",
                        "C:\\secret\\dataset.json"));

        EvaluationJob submitted = service.submitGoldenRag(
                "missing-dataset", owner);

        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            EvaluationJob loaded = service.get(submitted.jobId(), owner);
            assertThat(loaded.status()).isEqualTo("failed");
            assertThat(loaded.error())
                    .isEqualTo("Golden RAG evaluation failed "
                            + "(GOLDEN_DATASET_NOT_FOUND).");
            assertThat(loaded.error()).doesNotContain("secret");
        });
    }

    @Test
    void goldenRagJobFailsButKeepsReportWhenGateFails() throws Exception {
        ActorScope owner = actor("tenant-a", "user-1");
        Path markdown = tempDir.resolve("gate-report.md");
        Files.writeString(markdown, "# Failed gate\n");
        when(goldenEvaluationService.run(
                eq("rag-support-golden-v1"), anyString()))
                .thenReturn(goldenReport(markdown.toString(), failedGate()));

        EvaluationJob submitted = service.submitGoldenRag(
                "rag-support-golden-v1", owner);

        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            EvaluationJob loaded = service.get(submitted.jobId(), owner);
            assertThat(loaded.status()).isEqualTo("failed");
            assertThat(loaded.error()).isEqualTo(
                    "Golden RAG evaluation failed "
                            + "(GOLDEN_EVALUATION_GATE_FAILED).");
            assertThat(loaded.report())
                    .extracting("gate.passed")
                    .isEqualTo(false);
        });
        assertThat(service.markdownReport(submitted.jobId(), owner))
                .isEqualTo("# Failed gate\n");
    }

    private Path propertiesPath() {
        return Path.of(properties.getEval().getJobsPath());
    }

    private static ActorScope actor(String tenantId, String userId) {
        return new ActorScope(
                new UserId(userId),
                new TenantId(tenantId),
                Set.of(ActorRole.TENANT_ADMIN),
                Set.of(),
                "request-" + tenantId + "-" + userId);
    }

    private static Map<String, Object> job(String jobId, String tenantId, String userId) {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("job_id", jobId);
        if (tenantId != null) {
            job.put("tenant_id", tenantId);
        }
        if (userId != null) {
            job.put("owner_user_id", userId);
        }
        job.put("status", "succeeded");
        job.put("phase", "completed");
        job.put("progress", 100);
        job.put("created_at", "2026-11-18T00:00:00Z");
        return job;
    }

    private static GoldenEvaluationReport goldenReport(String markdownPath) {
        return goldenReport(markdownPath, passingGate());
    }

    private static GoldenEvaluationReport goldenReport(
            String markdownPath,
            GoldenEvaluationGate gate) {
        GoldenEvaluationReport.RetrievalMetrics retrieval =
                new GoldenEvaluationReport.RetrievalMetrics(
                        1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 1, 2);
        GoldenEvaluationReport.HandoffMetrics handoff =
                new GoldenEvaluationReport.HandoffMetrics(
                        1, 1, 1, 0, 0, 10, 0, 10, 0);
        return new GoldenEvaluationReport(
                "job-1",
                "rag-support-golden-v1",
                "a".repeat(64),
                "working-tree",
                "bge-small-zh-v1.5-onnx-fp32",
                new GoldenDataset.Provenance("synthetic", false, false, 0),
                "2026-11-18T00:00:00Z",
                new GoldenEvaluationReport.Totals(12, 12, 30, 20, 10, 20, 12, 8, 0),
                gate,
                Map.of("DEV", retrieval, "HOLDOUT", retrieval, "ALL", retrieval),
                Map.of("DEV", handoff, "HOLDOUT", handoff, "ALL", handoff),
                Map.of(),
                List.of(),
                List.of(),
                List.of("Synthetic dataset"),
                Path.of(markdownPath).resolveSibling("report.json").toString(),
                markdownPath);
    }

    private static GoldenEvaluationGate passingGate() {
        return new GoldenEvaluationGate(
                true,
                List.of(new GoldenEvaluationGate.GateCheck(
                        "retrieval.ALL.recall_at5",
                        "ALL",
                        true,
                        true,
                        ">= 0.9800",
                        "1.0000")),
                gateThresholds());
    }

    private static GoldenEvaluationGate failedGate() {
        return new GoldenEvaluationGate(
                true,
                List.of(new GoldenEvaluationGate.GateCheck(
                        "retrieval.DEV.recall_at5",
                        "DEV",
                        true,
                        false,
                        ">= 0.9800",
                        "0.9500")),
                gateThresholds());
    }

    private static GoldenEvaluationGate.Thresholds gateThresholds() {
        return new GoldenEvaluationGate.Thresholds(
                0.02, 0, 0.0, 0.95, 0.10, 0.10, 2000);
    }
}
