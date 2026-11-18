package com.echomind.saas.evaluation.golden;

import java.util.List;
import java.util.Objects;

public record GoldenEvaluationGate(
        boolean passed,
        List<GateCheck> checks,
        Thresholds thresholds
) {
    public GoldenEvaluationGate {
        checks = List.copyOf(Objects.requireNonNull(checks, "checks"));
        thresholds = Objects.requireNonNull(thresholds, "thresholds");
        passed = checks.stream()
                .filter(GateCheck::blocking)
                .allMatch(GateCheck::passed);
    }

    public long blockingFailureCount() {
        return checks.stream()
                .filter(GateCheck::blocking)
                .filter(check -> !check.passed())
                .count();
    }

    public long advisoryWarningCount() {
        return checks.stream()
                .filter(check -> !check.blocking())
                .filter(check -> !check.passed())
                .count();
    }

    public record GateCheck(
            String id,
            String split,
            boolean blocking,
            boolean passed,
            String expectation,
            String observed
    ) {
        public GateCheck {
            id = requireText(id, "id");
            split = requireText(split, "split");
            expectation = requireText(expectation, "expectation");
            observed = requireText(observed, "observed");
        }
    }

    public record Thresholds(
            double maxMetricDrop,
            int maxAclViolations,
            double maxDegradedRate,
            double minHandoffRecall,
            double maxFalseEscalationRate,
            double maxMissedHandoffRate,
            long p95LatencyAdvisoryMs
    ) {
        public Thresholds {
            requireRange(maxMetricDrop, "maxMetricDrop");
            if (maxAclViolations < 0) {
                throw new IllegalArgumentException("maxAclViolations must be >= 0");
            }
            requireRange(maxDegradedRate, "maxDegradedRate");
            requireRange(minHandoffRecall, "minHandoffRecall");
            requireRange(maxFalseEscalationRate, "maxFalseEscalationRate");
            requireRange(maxMissedHandoffRate, "maxMissedHandoffRate");
            if (p95LatencyAdvisoryMs <= 0) {
                throw new IllegalArgumentException(
                        "p95LatencyAdvisoryMs must be > 0");
            }
        }

        private static void requireRange(double value, String field) {
            if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
                throw new IllegalArgumentException(field + " must be in [0, 1]");
            }
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
