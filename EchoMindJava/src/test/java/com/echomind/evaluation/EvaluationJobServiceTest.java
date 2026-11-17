package com.echomind.evaluation;

import com.echomind.api.dto.EvalRunRequest;
import com.echomind.config.EchoMindProperties;
import com.echomind.saas.api.error.ResourceNotFoundException;
import com.echomind.saas.evaluation.domain.EvaluationJob;
import com.echomind.saas.identity.domain.ActorRole;
import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.identity.domain.TenantScopedActorKey;
import com.echomind.saas.identity.domain.UserId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvaluationJobServiceTest {

    private static final EvalRunRequest REQUEST = new EvalRunRequest(List.of(), List.of());

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private EndToEndEvaluator evaluator;
    private EchoMindProperties properties;
    private EvaluationJobService service;

    @BeforeEach
    void setUp() {
        evaluator = mock(EndToEndEvaluator.class);
        when(evaluator.run(any(), any())).thenReturn(Map.of("ok", true));
        properties = new EchoMindProperties();
        properties.getEval().setJobsPath(tempDir.resolve("jobs.json").toString());
        service = new EvaluationJobService(evaluator, objectMapper, properties);
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
        ActorScope secondOwner = actor("tenant-b", "user-2");
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
}
