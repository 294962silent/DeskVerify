package com.echomind.saas.evaluation.golden;

import com.echomind.saas.evaluation.golden.GoldenEvaluationReport.RetrievalCaseResult;
import com.echomind.saas.evaluation.golden.GoldenEvaluationReport.RetrievalMetrics;
import com.echomind.saas.identity.domain.ActorRole;
import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.identity.domain.UserId;
import com.echomind.saas.rag.application.KnowledgeRetrieval;
import com.echomind.saas.rag.domain.KnowledgeRetrievalResult;
import com.echomind.saas.rag.domain.RetrievalHit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class GoldenRagEvaluator {

    private static final int TOP_K = 5;

    private final KnowledgeRetrieval retrieval;

    public GoldenRagEvaluator(KnowledgeRetrieval retrieval) {
        this.retrieval = Objects.requireNonNull(retrieval, "retrieval");
    }

    public Outcome evaluate(GoldenDataset dataset) {
        List<RetrievalCaseResult> results = new ArrayList<>();
        for (GoldenDataset.RetrievalCase evaluationCase : dataset.retrievalCases()) {
            results.add(evaluateCase(dataset.tenantId(), evaluationCase));
        }
        Map<String, RetrievalMetrics> metrics = new java.util.LinkedHashMap<>();
        metrics.put("DEV", metrics(results.stream()
                .filter(result -> result.split() == GoldenDataset.Split.DEV)
                .toList()));
        metrics.put("HOLDOUT", metrics(results.stream()
                .filter(result -> result.split() == GoldenDataset.Split.HOLDOUT)
                .toList()));
        metrics.put("ALL", metrics(results));
        return new Outcome(Map.copyOf(metrics), List.copyOf(results));
    }

    private RetrievalCaseResult evaluateCase(
            String tenantId,
            GoldenDataset.RetrievalCase evaluationCase) {
        ActorScope actor = actor(tenantId, evaluationCase.actorScope());
        KnowledgeRetrievalResult result;
        try {
            result = retrieval.search(evaluationCase.query(), TOP_K, actor);
        } catch (RuntimeException exception) {
            return failedCase(evaluationCase, List.of("retrieval_failed"));
        }

        int expectedRank = expectedRank(result.hits(), evaluationCase.expectedChunkId());
        boolean documentHit = result.hits().stream()
                .map(RetrievalHit::chunk)
                .anyMatch(chunk -> chunk.documentId()
                        .equals(evaluationCase.expectedDocumentId()));
        boolean versionHit = result.hits().stream()
                .map(RetrievalHit::chunk)
                .anyMatch(chunk -> chunk.documentId()
                                .equals(evaluationCase.expectedDocumentId())
                        && chunk.versionNumber()
                        == evaluationCase.expectedVersionNumber());
        boolean chunkHit = expectedRank > 0;
        boolean citationHit = result.citations().stream()
                .anyMatch(citation -> citation.chunkId()
                        .equals(evaluationCase.expectedChunkId()));
        boolean aclViolation = aclViolation(actor, result.hits());
        boolean degraded = !result.degradedModes().isEmpty();
        boolean passed = chunkHit
                && (!evaluationCase.expectCitation() || citationHit)
                && !aclViolation
                && !degraded;
        String topChunkId = result.hits().isEmpty()
                ? null
                : result.hits().getFirst().chunk().chunkId();
        return new RetrievalCaseResult(
                evaluationCase.caseId(),
                evaluationCase.split(),
                evaluationCase.query(),
                evaluationCase.expectedDocumentId(),
                evaluationCase.expectedVersionNumber(),
                evaluationCase.expectedChunkId(),
                topChunkId,
                expectedRank,
                expectedRank > 0 && expectedRank <= TOP_K ? 1.0 / expectedRank : 0.0,
                documentHit,
                versionHit,
                chunkHit,
                citationHit,
                aclViolation,
                degraded,
                passed,
                result.degradedModes(),
                result.latencyMs());
    }

    private static RetrievalCaseResult failedCase(
            GoldenDataset.RetrievalCase evaluationCase,
            List<String> degradedModes) {
        return new RetrievalCaseResult(
                evaluationCase.caseId(),
                evaluationCase.split(),
                evaluationCase.query(),
                evaluationCase.expectedDocumentId(),
                evaluationCase.expectedVersionNumber(),
                evaluationCase.expectedChunkId(),
                null,
                0,
                0.0,
                false,
                false,
                false,
                false,
                false,
                true,
                false,
                degradedModes,
                0);
    }

    private static int expectedRank(
            List<RetrievalHit> hits,
            String expectedChunkId) {
        for (int index = 0; index < Math.min(TOP_K, hits.size()); index++) {
            if (hits.get(index).chunk().chunkId().equals(expectedChunkId)) {
                return index + 1;
            }
        }
        return 0;
    }

    private static boolean aclViolation(
            ActorScope actor,
            List<RetrievalHit> hits) {
        if (actor.hasRole(ActorRole.TENANT_ADMIN)
                || actor.hasRole(ActorRole.SUPPORT_AGENT)) {
            return false;
        }
        return hits.stream().map(RetrievalHit::chunk).anyMatch(chunk ->
                chunk.visibility() == com.echomind.saas.rag.domain.KnowledgeVisibility.CUSTOMER
                        && chunk.customerId() != null
                        && !actor.customerIds().contains(chunk.customerId()));
    }

    private static ActorScope actor(
            String tenantId,
            GoldenDataset.ActorScope actorScope) {
        return switch (actorScope) {
            case TENANT_ADMIN -> new ActorScope(
                    new UserId("golden-admin"),
                    new TenantId(tenantId),
                    Set.of(ActorRole.TENANT_ADMIN),
                    Set.of(),
                    "golden-evaluation");
            case CUSTOMER_A -> new ActorScope(
                    new UserId("golden-customer-a"),
                    new TenantId(tenantId),
                    Set.of(ActorRole.CUSTOMER),
                    Set.of("customer-a"),
                    "golden-evaluation");
            case CUSTOMER_B -> new ActorScope(
                    new UserId("golden-customer-b"),
                    new TenantId(tenantId),
                    Set.of(ActorRole.CUSTOMER),
                    Set.of("customer-b"),
                    "golden-evaluation");
        };
    }

    private static RetrievalMetrics metrics(List<RetrievalCaseResult> results) {
        if (results.isEmpty()) {
            return new RetrievalMetrics(
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        List<Long> latencies = results.stream()
                .map(RetrievalCaseResult::latencyMs)
                .sorted()
                .toList();
        return new RetrievalMetrics(
                ratio(results, result -> result.expectedRank() == 1),
                ratio(results, result -> result.expectedRank() > 0
                        && result.expectedRank() <= 3),
                ratio(results, GoldenEvaluationReport.RetrievalCaseResult::chunkHit),
                results.stream().mapToDouble(
                        GoldenEvaluationReport.RetrievalCaseResult::reciprocalRank)
                        .average().orElse(0.0),
                ratio(results, GoldenEvaluationReport.RetrievalCaseResult::documentHit),
                ratio(results, GoldenEvaluationReport.RetrievalCaseResult::versionHit),
                ratio(results, GoldenEvaluationReport.RetrievalCaseResult::chunkHit),
                ratio(results, GoldenEvaluationReport.RetrievalCaseResult::citationHit),
                (int) results.stream()
                        .filter(GoldenEvaluationReport.RetrievalCaseResult::aclViolation)
                        .count(),
                ratio(results, GoldenEvaluationReport.RetrievalCaseResult::degraded),
                percentile(latencies, 0.50),
                percentile(latencies, 0.95));
    }

    private static double ratio(
            List<RetrievalCaseResult> results,
            java.util.function.Predicate<RetrievalCaseResult> predicate) {
        return round((double) results.stream().filter(predicate).count()
                / results.size());
    }

    private static long percentile(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int rank = (int) Math.ceil(percentile * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    public record Outcome(
            Map<String, RetrievalMetrics> metrics,
            List<RetrievalCaseResult> cases
    ) {
    }
}
