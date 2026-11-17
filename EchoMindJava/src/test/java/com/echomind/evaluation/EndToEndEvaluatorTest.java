package com.echomind.evaluation;

import com.echomind.agent.AgentOrchestrator;
import com.echomind.agent.AgentRequest;
import com.echomind.agent.AgentType;
import com.echomind.agent.OrchestratorResult;
import com.echomind.api.dto.EvalRunRequest;
import com.echomind.config.EchoMindProperties;
import com.echomind.intent.IntentCategory;
import com.echomind.intent.IntentRecognizer;
import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.identity.domain.TenantScopedActorKey;
import com.echomind.saas.identity.domain.UserId;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EndToEndEvaluatorTest {

    @TempDir
    Path tempDir;

    @Test
    void sameUserAndConversationInDifferentTenantsUsesDistinctAgentKeys() {
        IntentRecognizer intentRecognizer = mock(IntentRecognizer.class);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        LLMJudge judge = mock(LLMJudge.class);
        EchoMindProperties properties = new EchoMindProperties();
        properties.getEval().setBaselinePath(tempDir.resolve("baseline.json").toString());
        EndToEndEvaluator evaluator = new EndToEndEvaluator(
                intentRecognizer, orchestrator, judge, new ObjectMapper(), properties);
        when(orchestrator.run(any(AgentRequest.class))).thenReturn(orchestratorResult());
        when(judge.judge(anyString(), anyString(), anyString()))
                .thenReturn(new QualityScores(1.0, 1.0, 1.0, 1.0, false, null));

        EvalRunRequest request = new EvalRunRequest(
                List.of(),
                List.of(new EvalRunRequest.DialogCase("hello", null, "shared-conversation")));
        UserId userId = new UserId("user-1");
        evaluator.run(request, new TenantScopedActorKey(new TenantId("tenant-a"), userId));
        evaluator.run(request, new TenantScopedActorKey(new TenantId("tenant-b"), userId));

        ArgumentCaptor<AgentRequest> requests = ArgumentCaptor.forClass(AgentRequest.class);
        verify(orchestrator, times(2)).run(requests.capture());
        assertThat(requests.getAllValues())
                .extracting(AgentRequest::userId)
                .containsExactly(
                        "tenant:tenant-a/user:user-1",
                        "tenant:tenant-b/user:user-1");
    }

    private static OrchestratorResult orchestratorResult() {
        return new OrchestratorResult(
                "request-result",
                "response",
                AgentType.GENERAL,
                IntentCategory.GREETING,
                false,
                1,
                List.of(AgentType.GENERAL),
                AgentType.GENERAL,
                List.of(),
                List.of(),
                List.of(),
                "test",
                1.0);
    }
}
