package com.echomind.evaluation;

import com.echomind.api.dto.EvalRunRequest;
import com.echomind.config.EchoMindProperties;
import com.echomind.saas.api.error.ResourceNotFoundException;
import com.echomind.saas.evaluation.domain.EvaluationJob;
import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.identity.domain.TenantScopedActorKey;
import com.echomind.saas.identity.domain.UserId;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 把耗时的端到端评测从 HTTP 请求生命周期中拆出。
 * 任务及完成报告持久化到本地文件，服务重启后可继续读取已完成报告。
 */
@Service
public class EvaluationJobService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationJobService.class);
    private static final int MAX_STORED_JOBS_PER_OWNER = 20;

    private final EndToEndEvaluator evaluator;
    private final ObjectMapper objectMapper;
    private final EchoMindProperties properties;
    private final Map<String, Map<String, Object>> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "echomind-evaluation-worker");
        thread.setDaemon(true);
        return thread;
    });

    public EvaluationJobService(EndToEndEvaluator evaluator, ObjectMapper objectMapper, EchoMindProperties properties) {
        this.evaluator = evaluator;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @PostConstruct
    public synchronized void load() {
        Path path = jobsPath();
        if (Files.exists(path)) {
            try {
                Map<String, Object> stored = objectMapper.readValue(path.toFile(), new TypeReference<>() { });
                Object rawJobs = stored.get("jobs");
                if (rawJobs instanceof List<?> values) {
                    for (Object value : values) {
                        if (value instanceof Map<?, ?> map && map.get("job_id") != null) {
                            Map<String, Object> job = new LinkedHashMap<>();
                            map.forEach((key, item) -> job.put(String.valueOf(key), item));
                            jobs.put(String.valueOf(map.get("job_id")), job);
                        }
                    }
                }
            } catch (Exception ex) {
                log.warn("Failed to load evaluation jobs: {}", ex.getMessage());
            }
        }

        boolean changed = false;
        List<String> legacyJobIds = jobs.entrySet().stream()
                .filter(entry -> !hasValidOwnership(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        if (!legacyJobIds.isEmpty()) {
            legacyJobIds.forEach(jobs::remove);
            changed = true;
        }
        for (Map<String, Object> job : jobs.values()) {
            String status = String.valueOf(job.get("status"));
            if ("queued".equals(status) || "running".equals(status)) {
                job.put("status", "failed");
                job.put("phase", "interrupted");
                job.put("error", "评测任务因服务重启而中断，请重新运行。");
                job.put("finished_at", now());
                changed = true;
            }
        }
        changed |= trim();
        if (changed) {
            save();
        }
    }

    public synchronized EvaluationJob submit(EvalRunRequest request, ActorScope actor) {
        if (actor == null) {
            throw new IllegalArgumentException("authenticated actor is required");
        }
        String jobId = UUID.randomUUID().toString();
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("job_id", jobId);
        job.put("tenant_id", actor.tenantId().value());
        job.put("owner_user_id", actor.userId().value());
        job.put("status", "queued");
        job.put("phase", "queued");
        job.put("progress", 0);
        job.put("created_at", now());
        job.put("started_at", null);
        job.put("finished_at", null);
        job.put("error", null);
        job.put("report", null);
        jobs.put(jobId, job);
        trim();
        save();
        TenantScopedActorKey actorKey = new TenantScopedActorKey(
                actor.tenantId(), actor.userId());
        executor.submit(() -> execute(jobId, request, actorKey));
        return publicJob(job);
    }

    public EvaluationJob get(String jobId, ActorScope actor) {
        if (actor == null) {
            throw new IllegalArgumentException("authenticated actor is required");
        }
        Map<String, Object> job = jobs.get(jobId);
        if (job == null || !ownedBy(job, actor)) {
            throw new ResourceNotFoundException();
        }
        return publicJob(job);
    }

    public List<EvaluationJob> list(ActorScope actor) {
        return list(actor, MAX_STORED_JOBS_PER_OWNER);
    }

    public List<EvaluationJob> list(ActorScope actor, int limit) {
        if (actor == null) {
            throw new IllegalArgumentException("authenticated actor is required");
        }
        int boundedLimit = Math.max(1, Math.min(limit, MAX_STORED_JOBS_PER_OWNER));
        return jobs.values().stream()
                .filter(job -> ownedBy(job, actor))
                .sorted(Comparator.comparing(job -> String.valueOf(job.getOrDefault("created_at", "")), Comparator.reverseOrder()))
                .limit(boundedLimit)
                .map(this::publicJob)
                .toList();
    }

    @PreDestroy
    public synchronized void close() {
        executor.shutdownNow();
        for (Map<String, Object> job : jobs.values()) {
            String status = String.valueOf(job.get("status"));
            if ("queued".equals(status) || "running".equals(status)) {
                job.put("status", "failed");
                job.put("phase", "interrupted");
                job.put("error", "评测任务因服务停止而中断，请重新运行。");
                job.put("finished_at", now());
            }
        }
        save();
    }

    private void execute(String jobId, EvalRunRequest request, TenantScopedActorKey actorKey) {
        Map<String, Object> job = jobs.get(jobId);
        if (job == null) {
            return;
        }
        synchronized (this) {
            job.put("status", "running");
            job.put("phase", "running");
            job.put("progress", 5);
            job.put("started_at", now());
            save();
        }
        try {
            Map<String, Object> report = evaluator.run(request, actorKey);
            synchronized (this) {
                job.put("status", "succeeded");
                job.put("phase", "completed");
                job.put("progress", 100);
                job.put("report", report);
                job.put("finished_at", now());
                save();
            }
        } catch (Exception ex) {
            log.error("Evaluation job failed: {}", jobId, ex);
            synchronized (this) {
                job.put("status", "failed");
                job.put("phase", "failed");
                job.put("error", "评测执行失败（" + ex.getClass().getSimpleName() + "），请查看服务日志。");
                job.put("finished_at", now());
                save();
            }
        }
    }

    private synchronized void save() {
        try {
            Path path = jobsPath();
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), Map.of("jobs", new ArrayList<>(jobs.values())));
        } catch (Exception ex) {
            log.warn("Failed to save evaluation jobs: {}", ex.getMessage());
        }
    }

    private boolean trim() {
        boolean changed = false;
        Map<OwnerKey, List<Map<String, Object>>> byOwner = jobs.values().stream()
                .collect(Collectors.groupingBy(this::ownerKey));
        for (List<Map<String, Object>> ownerJobs : byOwner.values()) {
            if (ownerJobs.size() <= MAX_STORED_JOBS_PER_OWNER) {
                continue;
            }
            List<String> expiredIds = ownerJobs.stream()
                    .sorted(Comparator.comparing(
                            job -> String.valueOf(job.getOrDefault("created_at", "")),
                            Comparator.reverseOrder()))
                    .skip(MAX_STORED_JOBS_PER_OWNER)
                    .map(job -> String.valueOf(job.get("job_id")))
                    .toList();
            expiredIds.forEach(jobs::remove);
            changed |= !expiredIds.isEmpty();
        }
        return changed;
    }

    @SuppressWarnings("unchecked")
    private EvaluationJob publicJob(Map<String, Object> job) {
        Object rawReport = job.get("report");
        Map<String, Object> report = rawReport instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : null;
        return new EvaluationJob(
                String.valueOf(job.get("job_id")),
                new TenantId(String.valueOf(job.get("tenant_id"))),
                new UserId(String.valueOf(job.get("owner_user_id"))),
                nullableString(job.get("status")),
                nullableString(job.get("phase")),
                job.get("progress") instanceof Number number ? number.intValue() : 0,
                nullableString(job.get("created_at")),
                nullableString(job.get("started_at")),
                nullableString(job.get("finished_at")),
                nullableString(job.get("error")),
                report
        );
    }

    private boolean ownedBy(Map<String, Object> job, ActorScope actor) {
        return actor.tenantId().value().equals(job.get("tenant_id"))
                && actor.userId().value().equals(job.get("owner_user_id"));
    }

    private boolean hasValidOwnership(Map<String, Object> job) {
        Object tenantId = job.get("tenant_id");
        Object ownerUserId = job.get("owner_user_id");
        if (!(tenantId instanceof String tenantValue) || tenantValue.isBlank()
                || !(ownerUserId instanceof String ownerValue) || ownerValue.isBlank()) {
            return false;
        }
        try {
            new TenantId(tenantValue);
            new UserId(ownerValue);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private OwnerKey ownerKey(Map<String, Object> job) {
        return new OwnerKey(
                String.valueOf(job.get("tenant_id")),
                String.valueOf(job.get("owner_user_id")));
    }

    private String nullableString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Path jobsPath() {
        return Path.of(properties.getEval().getJobsPath());
    }

    private String now() {
        return Instant.now().toString();
    }

    private record OwnerKey(String tenantId, String ownerUserId) {
    }
}
