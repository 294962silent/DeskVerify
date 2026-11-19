package com.echomind.agent;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class HandoffPolicy {

    public HandoffDecision decide(HandoffSignals signals) {
        if (signals.explicitHumanRequest()) {
            return handoff("EXPLICIT_HUMAN_REQUEST");
        }
        if (signals.criticalUrgency()) {
            return handoff("CRITICAL_URGENCY");
        }
        if (signals.securityOrPrivacy()) {
            return handoff("SECURITY_OR_PRIVACY");
        }
        if (signals.irreversibleAction()) {
            return handoff("IRREVERSIBLE_ACTION");
        }
        if (signals.monetaryDispute()) {
            return handoff("MONETARY_DISPUTE");
        }
        if (signals.verifiedKnowledgeAvailable()) {
            return new HandoffDecision(
                    false, "KNOWLEDGE_SUFFICIENT", List.of("VERIFIED_KNOWLEDGE"));
        }
        return new HandoffDecision(
                false, "SELF_SERVICE_AVAILABLE", List.of("SELF_SERVICE"));
    }

    private static HandoffDecision handoff(String reason) {
        return new HandoffDecision(true, reason, List.of(reason));
    }
}
