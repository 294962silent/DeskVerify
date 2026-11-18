package com.echomind.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.echomind.intent.IntentCategory;
import com.echomind.intent.IntentRecognizer;
import com.echomind.intent.IntentResult;
import com.echomind.intent.UrgencyLevel;
import com.echomind.trace.RequestTraceStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentOrchestratorHandoffTest {

    @Test
    void orchestratorEscalatesSecurityRiskThroughSharedPolicy() {
        IntentRecognizer intentRecognizer = mock(IntentRecognizer.class);
        when(intentRecognizer.recognize(any(), any())).thenReturn(new IntentResult(
                IntentCategory.QUERY,
                0.9,
                UrgencyLevel.LOW,
                "account",
                Map.of(),
                "test",
                1,
                Map.of()));
        BaseAgent agent = mock(BaseAgent.class);
        when(agent.type()).thenReturn(AgentType.GENERAL);
        when(agent.handle(any())).thenReturn(new AgentResponse(
                AgentType.GENERAL,
                "请立即重置密码",
                true,
                0.9,
                1,
                false,
                "",
                true,
                false,
                false,
                ""));
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                intentRecognizer,
                Map.of(AgentType.GENERAL, List.of(agent)),
                mock(RequestTraceStore.class),
                new HandoffPolicy(),
                new HandoffSignalExtractor());

        OrchestratorResult result = orchestrator.run(AgentRequest.of(
                "我发现账号异地登录，担心被盗",
                "user-1",
                "conversation-1",
                "",
                List.of()));

        assertThat(result.escalated()).isTrue();
    }
}
