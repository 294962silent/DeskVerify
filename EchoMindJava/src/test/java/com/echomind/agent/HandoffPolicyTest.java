package com.echomind.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HandoffPolicyTest {

    private final HandoffPolicy policy = new HandoffPolicy();

    @Test
    void explicitHumanRequestAlwaysEscalates() {
        HandoffDecision decision = policy.decide(signals(
                true, false, false, false, false, true));

        assertThat(decision.handoff()).isTrue();
        assertThat(decision.reason()).isEqualTo("EXPLICIT_HUMAN_REQUEST");
    }

    @Test
    void appliesRiskPriorityBeforeKnowledgeAvailability() {
        HandoffDecision decision = policy.decide(signals(
                false, true, true, true, true, true));

        assertThat(decision.handoff()).isTrue();
        assertThat(decision.reason()).isEqualTo("CRITICAL_URGENCY");
    }

    @Test
    void handlesSecurityIrreversibleAndMonetaryDisputes() {
        assertThat(policy.decide(signals(
                false, false, true, false, false, true)).reason())
                .isEqualTo("SECURITY_OR_PRIVACY");
        assertThat(policy.decide(signals(
                false, false, false, false, true, true)).reason())
                .isEqualTo("IRREVERSIBLE_ACTION");
        assertThat(policy.decide(signals(
                false, false, false, true, false, true)).reason())
                .isEqualTo("MONETARY_DISPUTE");
    }

    @Test
    void verifiedKnowledgeSuppressesHandoffWhenNoRiskSignalExists() {
        HandoffDecision decision = policy.decide(signals(
                false, false, false, false, false, true));

        assertThat(decision.handoff()).isFalse();
        assertThat(decision.reason()).isEqualTo("KNOWLEDGE_SUFFICIENT");
    }

    @Test
    void selfServicePathDoesNotEscalate() {
        HandoffDecision decision = policy.decide(signals(
                false, false, false, false, false, false));

        assertThat(decision.handoff()).isFalse();
        assertThat(decision.reason()).isEqualTo("SELF_SERVICE_AVAILABLE");
    }

    private static HandoffSignals signals(
            boolean explicit,
            boolean critical,
            boolean security,
            boolean monetary,
            boolean irreversible,
            boolean knowledge) {
        return new HandoffSignals(
                explicit, critical, security, monetary, irreversible, knowledge);
    }
}
