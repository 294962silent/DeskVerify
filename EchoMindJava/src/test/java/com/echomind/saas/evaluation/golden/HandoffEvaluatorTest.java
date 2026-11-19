package com.echomind.saas.evaluation.golden;

import static org.assertj.core.api.Assertions.assertThat;

import com.echomind.agent.HandoffPolicy;
import com.echomind.saas.rag.application.MarkdownAwareChunker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;

class HandoffEvaluatorTest {

    @Test
    void evaluatesAllGoldenHandoffCasesWithSharedPolicy() {
        ObjectMapper objectMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        GoldenDataset dataset = new GoldenDatasetLoader(
                objectMapper, new MarkdownAwareChunker())
                .load("rag-support-golden-v1");
        HandoffEvaluator evaluator = new HandoffEvaluator(new HandoffPolicy());

        HandoffEvaluator.Outcome outcome = evaluator.evaluate(
                dataset.handoffCases());

        GoldenEvaluationReport.HandoffMetrics all = outcome.metrics().get("ALL");
        assertThat(all.accuracy()).isEqualTo(1.0);
        assertThat(all.precision()).isEqualTo(1.0);
        assertThat(all.recall()).isEqualTo(1.0);
        assertThat(all.falseEscalationRate()).isZero();
        assertThat(all.missedHandoffRate()).isZero();
        assertThat(all.truePositive()).isEqualTo(10);
        assertThat(all.trueNegative()).isEqualTo(10);
        assertThat(outcome.cases()).hasSize(20).allMatch(
                GoldenEvaluationReport.HandoffCaseResult::passed);
    }
}
