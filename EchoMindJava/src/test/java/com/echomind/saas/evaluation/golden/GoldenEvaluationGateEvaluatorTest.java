package com.echomind.saas.evaluation.golden;

import static org.assertj.core.api.Assertions.assertThat;

import com.echomind.config.EchoMindProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GoldenEvaluationGateEvaluatorTest {

    private static final String VERSION = "rag-support-golden-v1";
    private static final String DIGEST =
            "d394771cd02c81d230225979f7015c1ef6b4e2f850ddfefb9ef70e59926cd968";

    @Test
    void passesWhenMetricsMeetTheVersionedBaseline() {
        GoldenEvaluationGate gate = evaluator().evaluate(
                VERSION,
                DIGEST,
                perfectRetrieval(),
                perfectHandoff());

        assertThat(gate.passed()).isTrue();
        assertThat(gate.checks())
                .allMatch(check -> !check.blocking() || check.passed());
        assertThat(gate.checks())
                .extracting(GoldenEvaluationGate.GateCheck::id)
                .contains(
                        "baseline.dataset_sha256",
                        "retrieval.DEV.recall_at5",
                        "retrieval.HOLDOUT.citation_hit_rate",
                        "handoff.ALL.recall");
    }

    @Test
    void failsWhenRecallOrAclRegresses() {
        GoldenEvaluationGate gate = evaluator().evaluate(
                VERSION,
                DIGEST,
                Map.of(
                        "DEV", retrieval(0.97, 1.0, 0, 0.0, 44),
                        "HOLDOUT", retrieval(1.0, 1.0, 0, 0.0, 37),
                        "ALL", retrieval(0.97, 1.0, 1, 0.0, 44)),
                perfectHandoff());

        assertThat(gate.passed()).isFalse();
        assertThat(failedBlockingIds(gate))
                .contains(
                        "retrieval.DEV.recall_at5",
                        "retrieval.ALL.acl_violations");
    }

    @Test
    void reportsP95AsAdvisoryWithoutBlockingTheGate() {
        GoldenEvaluationGate gate = evaluator().evaluate(
                VERSION,
                DIGEST,
                Map.of(
                        "DEV", retrieval(1.0, 1.0, 0, 0.0, 44),
                        "HOLDOUT", retrieval(1.0, 1.0, 0, 0.0, 37),
                        "ALL", retrieval(1.0, 1.0, 0, 0.0, 2_500)),
                perfectHandoff());

        assertThat(gate.passed()).isTrue();
        assertThat(gate.checks())
                .filteredOn(check -> check.id().equals("retrieval.ALL.p95_latency_ms"))
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.blocking()).isFalse();
                    assertThat(check.passed()).isFalse();
                });
    }

    @Test
    void failsWhenBaselineDigestDoesNotMatchTheDataset() {
        GoldenEvaluationGate gate = evaluator().evaluate(
                VERSION,
                "b".repeat(64),
                perfectRetrieval(),
                perfectHandoff());

        assertThat(gate.passed()).isFalse();
        assertThat(failedBlockingIds(gate))
                .containsExactly("baseline.dataset_sha256");
    }

    private static GoldenEvaluationGateEvaluator evaluator() {
        ObjectMapper objectMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        GoldenEvaluationBaselineLoader loader =
                new GoldenEvaluationBaselineLoader(objectMapper);
        return new GoldenEvaluationGateEvaluator(loader, new EchoMindProperties());
    }

    private static Map<String, GoldenEvaluationReport.RetrievalMetrics>
            perfectRetrieval() {
        return Map.of(
                "DEV", retrieval(1.0, 1.0, 0, 0.0, 44),
                "HOLDOUT", retrieval(1.0, 1.0, 0, 0.0, 37),
                "ALL", retrieval(1.0, 1.0, 0, 0.0, 44));
    }

    private static GoldenEvaluationReport.RetrievalMetrics retrieval(
            double recallAt5,
            double citationHitRate,
            int aclViolations,
            double degradedRate,
            long p95LatencyMs) {
        return new GoldenEvaluationReport.RetrievalMetrics(
                recallAt5,
                recallAt5,
                recallAt5,
                recallAt5,
                recallAt5,
                recallAt5,
                recallAt5,
                citationHitRate,
                aclViolations,
                degradedRate,
                Math.max(1, p95LatencyMs - 10),
                p95LatencyMs);
    }

    private static Map<String, GoldenEvaluationReport.HandoffMetrics>
            perfectHandoff() {
        return Map.of(
                "ALL",
                new GoldenEvaluationReport.HandoffMetrics(
                        1.0, 1.0, 1.0, 0.0, 0.0, 10, 0, 10, 0));
    }

    private static List<String> failedBlockingIds(GoldenEvaluationGate gate) {
        return gate.checks().stream()
                .filter(GoldenEvaluationGate.GateCheck::blocking)
                .filter(check -> !check.passed())
                .map(GoldenEvaluationGate.GateCheck::id)
                .toList();
    }
}
