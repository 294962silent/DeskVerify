package com.echomind.agent;

import java.util.List;
import java.util.Objects;

public record HandoffDecision(
        boolean handoff,
        String reason,
        List<String> triggeredRules
) {
    public HandoffDecision {
        reason = Objects.requireNonNull(reason, "reason");
        triggeredRules = List.copyOf(
                Objects.requireNonNull(triggeredRules, "triggeredRules"));
    }
}
