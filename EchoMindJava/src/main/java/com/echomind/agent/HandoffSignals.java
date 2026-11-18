package com.echomind.agent;

public record HandoffSignals(
        boolean explicitHumanRequest,
        boolean criticalUrgency,
        boolean securityOrPrivacy,
        boolean monetaryDispute,
        boolean irreversibleAction,
        boolean verifiedKnowledgeAvailable
) {
}
