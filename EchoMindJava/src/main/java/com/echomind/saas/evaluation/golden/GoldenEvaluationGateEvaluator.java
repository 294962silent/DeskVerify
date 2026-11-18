package com.echomind.saas.evaluation.golden;

import com.echomind.config.EchoMindProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class GoldenEvaluationGateEvaluator {

    private static final double EPSILON = 1.0e-9;
    private static final List<String> QUALITY_SPLITS = List.of("DEV", "HOLDOUT");
    private static final List<String> ACL_SPLITS = List.of("DEV", "HOLDOUT", "ALL");

    private final GoldenEvaluationBaselineLoader baselineLoader;
    private final EchoMindProperties properties;

    public GoldenEvaluationGateEvaluator(
            GoldenEvaluationBaselineLoader baselineLoader,
            EchoMindProperties properties) {
        this.baselineLoader = Objects.requireNonNull(
                baselineLoader, "baselineLoader");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public GoldenEvaluationGate evaluate(
            String datasetVersion,
            String datasetSha256,
            Map<String, GoldenEvaluationReport.RetrievalMetrics> retrievalMetrics,
            Map<String, GoldenEvaluationReport.HandoffMetrics> handoffMetrics) {
        GoldenEvaluationBaseline baseline = baselineLoader.load(datasetVersion);
        EchoMindProperties.GoldenGate configured =
                properties.getEval().getGoldenGate();
        GoldenEvaluationGate.Thresholds thresholds =
                new GoldenEvaluationGate.Thresholds(
                        configured.getMaxMetricDrop(),
                        configured.getMaxAclViolations(),
                        configured.getMaxDegradedRate(),
                        configured.getMinHandoffRecall(),
                        configured.getMaxFalseEscalationRate(),
                        configured.getMaxMissedHandoffRate(),
                        configured.getP95LatencyAdvisoryMs());
        List<GoldenEvaluationGate.GateCheck> checks = new ArrayList<>();

        addCheck(
                checks,
                "baseline.dataset_version",
                "ALL",
                true,
                datasetVersion.equals(baseline.datasetVersion()),
                "equals " + baseline.datasetVersion(),
                datasetVersion);
        addCheck(
                checks,
                "baseline.dataset_sha256",
                "ALL",
                true,
                datasetSha256 != null
                        && datasetSha256.equalsIgnoreCase(baseline.datasetSha256()),
                "equals " + baseline.datasetSha256(),
                String.valueOf(datasetSha256));

        for (String split : QUALITY_SPLITS) {
            GoldenEvaluationReport.RetrievalMetrics observed =
                    retrievalMetrics.get(split);
            GoldenEvaluationReport.RetrievalMetrics expected =
                    baseline.retrievalMetrics().get(split);
            if (observed == null || expected == null) {
                addMissingCheck(checks, "retrieval." + split, split);
                continue;
            }
            addRelativeMinimum(
                    checks,
                    "retrieval." + split + ".recall_at5",
                    split,
                    observed.recallAt5(),
                    expected.recallAt5(),
                    configured.getMaxMetricDrop());
            addRelativeMinimum(
                    checks,
                    "retrieval." + split + ".mrr_at5",
                    split,
                    observed.mrrAt5(),
                    expected.mrrAt5(),
                    configured.getMaxMetricDrop());
            addRelativeMinimum(
                    checks,
                    "retrieval." + split + ".citation_hit_rate",
                    split,
                    observed.citationHitRate(),
                    expected.citationHitRate(),
                    configured.getMaxMetricDrop());
        }

        for (String split : ACL_SPLITS) {
            GoldenEvaluationReport.RetrievalMetrics observed =
                    retrievalMetrics.get(split);
            if (observed == null) {
                addMissingCheck(checks, "retrieval." + split, split);
                continue;
            }
            addCheck(
                    checks,
                    "retrieval." + split + ".acl_violations",
                    split,
                    true,
                    observed.aclViolations() <= configured.getMaxAclViolations(),
                    "<= " + configured.getMaxAclViolations(),
                    Integer.toString(observed.aclViolations()));
            addCheck(
                    checks,
                    "retrieval." + split + ".degraded_rate",
                    split,
                    true,
                    observed.degradedRate()
                            <= configured.getMaxDegradedRate() + EPSILON,
                    "<= " + format(configured.getMaxDegradedRate()),
                    format(observed.degradedRate()));
        }

        GoldenEvaluationReport.HandoffMetrics handoff =
                handoffMetrics.get("ALL");
        if (handoff == null) {
            addMissingCheck(checks, "handoff.ALL", "ALL");
        } else {
            addMinimum(
                    checks,
                    "handoff.ALL.recall",
                    handoff.recall(),
                    configured.getMinHandoffRecall());
            addMaximum(
                    checks,
                    "handoff.ALL.false_escalation_rate",
                    handoff.falseEscalationRate(),
                    configured.getMaxFalseEscalationRate());
            addMaximum(
                    checks,
                    "handoff.ALL.missed_handoff_rate",
                    handoff.missedHandoffRate(),
                    configured.getMaxMissedHandoffRate());
        }

        GoldenEvaluationReport.RetrievalMetrics all = retrievalMetrics.get("ALL");
        if (all != null) {
            addCheck(
                    checks,
                    "retrieval.ALL.p95_latency_ms",
                    "ALL",
                    false,
                    all.p95LatencyMs() <= configured.getP95LatencyAdvisoryMs(),
                    "<= " + configured.getP95LatencyAdvisoryMs(),
                    Long.toString(all.p95LatencyMs()));
        }
        return new GoldenEvaluationGate(true, checks, thresholds);
    }

    private static void addRelativeMinimum(
            List<GoldenEvaluationGate.GateCheck> checks,
            String id,
            String split,
            double observed,
            double baseline,
            double allowedDrop) {
        addCheck(
                checks,
                id,
                split,
                true,
                observed + EPSILON >= baseline - allowedDrop,
                ">= " + format(baseline - allowedDrop),
                format(observed));
    }

    private static void addMinimum(
            List<GoldenEvaluationGate.GateCheck> checks,
            String id,
            double observed,
            double minimum) {
        addCheck(
                checks,
                id,
                "ALL",
                true,
                observed + EPSILON >= minimum,
                ">= " + format(minimum),
                format(observed));
    }

    private static void addMaximum(
            List<GoldenEvaluationGate.GateCheck> checks,
            String id,
            double observed,
            double maximum) {
        addCheck(
                checks,
                id,
                "ALL",
                true,
                observed <= maximum + EPSILON,
                "<= " + format(maximum),
                format(observed));
    }

    private static void addMissingCheck(
            List<GoldenEvaluationGate.GateCheck> checks,
            String id,
            String split) {
        addCheck(
                checks,
                id,
                split,
                true,
                false,
                "metric present",
                "missing");
    }

    private static void addCheck(
            List<GoldenEvaluationGate.GateCheck> checks,
            String id,
            String split,
            boolean blocking,
            boolean passed,
            String expectation,
            String observed) {
        checks.add(new GoldenEvaluationGate.GateCheck(
                id, split, blocking, passed, expectation, observed));
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
