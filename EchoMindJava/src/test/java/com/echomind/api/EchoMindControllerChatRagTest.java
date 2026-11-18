package com.echomind.api;

import com.echomind.agent.AgentOrchestrator;
import com.echomind.agent.AgentRequest;
import com.echomind.agent.AgentType;
import com.echomind.agent.AnswerVerifier;
import com.echomind.agent.OrchestratorResult;
import com.echomind.api.dto.ChatRequest;
import com.echomind.api.dto.ChatResponse;
import com.echomind.evaluation.EndToEndEvaluator;
import com.echomind.evaluation.EvaluationJobService;
import com.echomind.intent.IntentCategory;
import com.echomind.intent.IntentRecognizer;
import com.echomind.intent.IntentResult;
import com.echomind.intent.UrgencyLevel;
import com.echomind.knowledge.KnowledgeBaseService;
import com.echomind.memory.MemoryContext;
import com.echomind.memory.MemoryManager;
import com.echomind.monitor.PerformanceMonitor;
import com.echomind.saas.identity.domain.ActorRole;
import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.identity.domain.UserId;
import com.echomind.saas.rag.application.KnowledgeRetrieval;
import com.echomind.saas.rag.domain.KnowledgeChunk;
import com.echomind.saas.rag.domain.KnowledgeCitation;
import com.echomind.saas.rag.domain.KnowledgeRetrievalResult;
import com.echomind.saas.rag.domain.KnowledgeVisibility;
import com.echomind.saas.rag.domain.RetrievalHit;
import com.echomind.skill.SkillManager;
import com.echomind.tool.KnowledgeToolManager;
import com.echomind.trace.ToolCallTrace;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EchoMindControllerChatRagTest {

    private final AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
    private final IntentRecognizer intentRecognizer = mock(IntentRecognizer.class);
    private final MemoryManager memoryManager = mock(MemoryManager.class);
    private final KnowledgeToolManager legacyKnowledge = mock(KnowledgeToolManager.class);
    private final KnowledgeRetrieval knowledgeRetrieval = mock(KnowledgeRetrieval.class);
    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
    private final AnswerVerifier answerVerifier = mock(AnswerVerifier.class);
    private final EchoMindController controller = new EchoMindController(
            orchestrator,
            intentRecognizer,
            memoryManager,
            legacyKnowledge,
            knowledgeRetrieval,
            knowledgeBaseService,
            answerVerifier,
            mock(PerformanceMonitor.class),
            mock(EndToEndEvaluator.class),
            mock(EvaluationJobService.class),
            mock(SkillManager.class),
            new ObjectMapper(),
            mock(PrometheusMeterRegistry.class));

    @Test
    void chatUsesVersionedEvidenceAndReturnsStructuredCitationsWithDegradedModes()
            throws Exception {
        stubChatBase();
        KnowledgeRetrievalResult retrieval = retrievalResult();
        when(knowledgeRetrieval.search(
                "企业版续费后席位多久生效", 4, actor()))
                .thenReturn(retrieval);

        ChatResponse response = controller.chat(
                new ChatRequest("企业版续费后席位多久生效", null),
                actor());

        assertThat(response.knowledgeUsed()).isTrue();
        assertThat(response.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.citationId()).isEqualTo("citation-1");
            assertThat(citation.documentTitle()).isEqualTo("企业版续费与席位");
            assertThat(citation.versionNumber()).isEqualTo(2);
            assertThat(citation.headingPath()).isEqualTo("套餐与权益 > 续费规则");
        });
        assertThat(response.retrievalDegradedModes())
                .containsExactly("lexical_unavailable");

        ArgumentCaptor<AgentRequest> request = ArgumentCaptor.forClass(AgentRequest.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ToolCallTrace>> traces = ArgumentCaptor.forClass(List.class);
        verify(orchestrator).run(request.capture(), traces.capture(), any());
        assertThat(request.getValue().context())
                .contains("[citation-1]")
                .contains("企业版续费与席位 v2")
                .contains("[检索降级: lexical_unavailable]")
                .contains("续费后五分钟内更新席位");
        assertThat(traces.getValue()).singleElement().satisfies(trace -> {
            assertThat(trace.toolName()).isEqualTo("versioned_knowledge_search");
            assertThat(trace.success()).isTrue();
            assertThat(trace.fallbackUsed()).isTrue();
            assertThat(trace.reranked()).isTrue();
        });
    }

    @Test
    void retrievalFailureStillAllowsChatButMarksItDegraded() throws Exception {
        stubChatBase();
        when(knowledgeRetrieval.search(anyString(), anyInt(), any()))
                .thenThrow(new IllegalStateException("search unavailable"));

        ChatResponse response = controller.chat(
                new ChatRequest("退款多久到账", null),
                actor());

        assertThat(response.knowledgeUsed()).isFalse();
        assertThat(response.citations()).isEmpty();
        assertThat(response.retrievalDegradedModes()).containsExactly("retrieval_failed");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ToolCallTrace>> traces = ArgumentCaptor.forClass(List.class);
        verify(orchestrator).run(any(), traces.capture(), any());
        assertThat(traces.getValue().getFirst().success()).isFalse();
        assertThat(traces.getValue().getFirst().fallbackUsed()).isTrue();
        assertThat(traces.getValue().getFirst().error()).contains("retrieval_failed");
    }

    private void stubChatBase() {
        when(memoryManager.getContext(anyString(), anyString(), anyString()))
                .thenReturn(new MemoryContext(List.of(), List.of(), Map.of(), ""));
        when(intentRecognizer.recognize(anyString(), anyList())).thenReturn(
                new IntentResult(
                        IntentCategory.QUERY,
                        0.93,
                        UrgencyLevel.LOW,
                        "knowledge",
                        Map.of(),
                        "test",
                        1,
                        Map.of()));
        when(orchestrator.run(any(AgentRequest.class), anyList(), any())).thenReturn(
                new OrchestratorResult(
                        "request-1",
                        "续费后五分钟内生效。",
                        AgentType.GENERAL,
                        IntentCategory.QUERY,
                        false,
                        20,
                        List.of(AgentType.GENERAL),
                        AgentType.GENERAL,
                        List.of(),
                        List.of(),
                        List.of(),
                        "knowledge route",
                        0.9));
        when(answerVerifier.verify(anyString(), anyString(), anyString()))
                .thenReturn(new AnswerVerifier.VerificationResult(
                        true, true, false, "grounded"));
    }

    private static KnowledgeRetrievalResult retrievalResult() {
        KnowledgeChunk chunk = new KnowledgeChunk(
                "chunk-1",
                "doc-1",
                2,
                0,
                "企业版续费与席位",
                "续费后五分钟内更新席位和权益。",
                null,
                "续费规则",
                "套餐与权益 > 续费规则",
                10,
                33,
                "a".repeat(64),
                KnowledgeVisibility.TENANT,
                null,
                new double[]{1.0, 0.0});
        RetrievalHit hit = new RetrievalHit(
                chunk, 0.0, 0.8, Integer.MAX_VALUE, 1, 0.016, 0.65);
        KnowledgeCitation citation = new KnowledgeCitation(
                "citation-1",
                "chunk-1",
                "doc-1",
                "企业版续费与席位",
                2,
                null,
                "续费规则",
                "套餐与权益 > 续费规则",
                chunk.content(),
                10,
                33,
                0.65);
        return new KnowledgeRetrievalResult(
                "企业版续费后席位多久生效",
                List.of(hit),
                List.of(citation),
                List.of("lexical_unavailable"),
                15);
    }

    private static ActorScope actor() {
        return new ActorScope(
                new UserId("customer-1"),
                new TenantId("tenant-a"),
                Set.of(ActorRole.CUSTOMER),
                Set.of("customer-a"),
                "chat-rag");
    }
}
