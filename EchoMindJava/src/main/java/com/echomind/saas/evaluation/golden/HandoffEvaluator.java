package com.echomind.saas.evaluation.golden;

import com.echomind.agent.HandoffDecision;
import com.echomind.agent.HandoffPolicy;
import com.echomind.agent.HandoffSignals;
import com.echomind.saas.evaluation.golden.GoldenEvaluationReport.HandoffCaseResult;
import com.echomind.saas.evaluation.golden.GoldenEvaluationReport.HandoffMetrics;
import com.echomind.saas.evaluation.golden.GoldenEvaluationReport.ReasonMetrics;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class HandoffEvaluator {

    private final HandoffPolicy policy;

    public HandoffEvaluator(HandoffPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public Outcome evaluate(List<GoldenDataset.HandoffCase> cases) {
        List<HandoffCaseResult> results = cases.stream()
                .map(this::evaluateCase)
                .toList();
        Map<String, HandoffMetrics> metrics = new LinkedHashMap<>();
        metrics.put("DEV", metrics(results.stream()
                .filter(result -> result.split() == GoldenDataset.Split.DEV)
                .toList()));
        metrics.put("HOLDOUT", metrics(results.stream()
                .filter(result -> result.split() == GoldenDataset.Split.HOLDOUT)
                .toList()));
        metrics.put("ALL", metrics(results));
        return new Outcome(
                Map.copyOf(metrics),
                results,
                reasonMetrics(cases, results));
    }

    private HandoffCaseResult evaluateCase(GoldenDataset.HandoffCase evaluationCase) {
        GoldenDataset.HandoffSignals signals = evaluationCase.signals();
        HandoffDecision decision = policy.decide(new HandoffSignals(
                signals.explicitHumanRequest(),
                signals.criticalUrgency(),
                signals.securityOrPrivacy(),
                signals.monetaryDispute(),
                signals.irreversibleAction(),
                signals.verifiedKnowledgeAvailable()));
        return new HandoffCaseResult(
                evaluationCase.caseId(),
                evaluationCase.split(),
                evaluationCase.message(),
                evaluationCase.expectedHandoff(),
                decision.handoff(),
                evaluationCase.expectedReason(),
                decision.reason(),
                decision.handoff() == evaluationCase.expectedHandoff());
    }

    private static HandoffMetrics metrics(List<HandoffCaseResult> results) {
        if (results.isEmpty()) {
            return new HandoffMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        int tp = 0;
        int fp = 0;
        int tn = 0;
        int fn = 0;
        for (HandoffCaseResult result : results) {
            if (result.expectedHandoff() && result.actualHandoff()) {
                tp++;
            } else if (!result.expectedHandoff() && result.actualHandoff()) {
                fp++;
            } else if (!result.expectedHandoff() && !result.actualHandoff()) {
                tn++;
            } else {
                fn++;
            }
        }
        double precision = tp + fp == 0 ? 0.0 : (double) tp / (tp + fp);
        double recall = tp + fn == 0 ? 0.0 : (double) tp / (tp + fn);
        return new HandoffMetrics(
                round((double) (tp + tn) / results.size()),
                round(precision),
                round(recall),
                round(fp + tn == 0 ? 0.0 : (double) fp / (fp + tn)),
                round(tp + fn == 0 ? 0.0 : (double) fn / (tp + fn)),
                tp,
                fp,
                tn,
                fn);
    }

    private static Map<String, Map<String, ReasonMetrics>> reasonMetrics(
            List<GoldenDataset.HandoffCase> cases,
            List<HandoffCaseResult> results) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        Map<String, Integer> correct = new LinkedHashMap<>();
        for (GoldenDataset.HandoffCase evaluationCase : cases) {
            totals.merge(evaluationCase.expectedReason(), 1, Integer::sum);
        }
        for (HandoffCaseResult result : results) {
            if (result.passed()) {
                correct.merge(result.expectedReason(), 1, Integer::sum);
            }
        }
        Map<String, Map<String, ReasonMetrics>> splitMetrics = new LinkedHashMap<>();
        splitMetrics.put("ALL", reasonMetrics(totals, correct));
        for (GoldenDataset.Split split : GoldenDataset.Split.values()) {
            Map<String, Integer> splitTotals = new LinkedHashMap<>();
            Map<String, Integer> splitCorrect = new LinkedHashMap<>();
            for (GoldenDataset.HandoffCase evaluationCase : cases) {
                if (evaluationCase.split() == split) {
                    splitTotals.merge(evaluationCase.expectedReason(), 1, Integer::sum);
                }
            }
            for (HandoffCaseResult result : results) {
                if (result.split() == split && result.passed()) {
                    splitCorrect.merge(result.expectedReason(), 1, Integer::sum);
                }
            }
            splitMetrics.put(split.name(), reasonMetrics(splitTotals, splitCorrect));
        }
        return Map.copyOf(splitMetrics);
    }

    private static Map<String, ReasonMetrics> reasonMetrics(
            Map<String, Integer> totals,
            Map<String, Integer> correct) {
        Map<String, ReasonMetrics> metrics = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : totals.entrySet()) {
            metrics.put(entry.getKey(), new ReasonMetrics(
                    entry.getValue(),
                    correct.getOrDefault(entry.getKey(), 0)));
        }
        return Map.copyOf(metrics);
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    public record Outcome(
            Map<String, HandoffMetrics> metrics,
            List<HandoffCaseResult> cases,
            Map<String, Map<String, ReasonMetrics>> reasonMetrics
    ) {
    }
}
