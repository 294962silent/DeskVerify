package com.echomind.trace;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

public record RequestToolTrace(
        String requestId,
        String timestamp,
        String endpoint,
        String userId,
        String conversationId,
        String intent,
        String intentGroup,
        String agentType,
        String primaryAgent,
        List<String> supportingAgents,
        List<String> toolsUsed,
        List<ToolCallTrace> toolCalls,
        boolean knowledgeUsed,
        boolean escalated,
        long latencyMs,
        @JsonIgnore String tenantId,
        @JsonIgnore String actorUserId
) {
    public RequestToolTrace(
            String requestId,
            String timestamp,
            String endpoint,
            String userId,
            String conversationId,
            String intent,
            String intentGroup,
            String agentType,
            String primaryAgent,
            List<String> supportingAgents,
            List<String> toolsUsed,
            List<ToolCallTrace> toolCalls,
            boolean knowledgeUsed,
            boolean escalated,
            long latencyMs
    ) {
        this(requestId, timestamp, endpoint, userId, conversationId, intent, intentGroup,
                agentType, primaryAgent, supportingAgents, toolsUsed, toolCalls,
                knowledgeUsed, escalated, latencyMs, null, null);
    }
}
