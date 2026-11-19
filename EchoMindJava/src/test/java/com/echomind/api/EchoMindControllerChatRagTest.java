package com.echomind.api;

import com.echomind.agent.AgentOrchestrator;
import com.echomind.agent.AgentRequest;
import com.echomind.agent.AgentType;
import com.echomind.agent.AnswerVerifier;
import com.echomind.agent.HandoffPolicy;
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
import com.echomind.saas.api.error.ResourceNotFoundException;
import com.echomind.saas.commerce.application.CommerceAccessPolicy;
import com.echomind.saas.commerce.application.CommerceQuery;
import com.echomind.saas.commerce.domain.CommercialSnapshot;
import com.echomind.saas.commerce.domain.CommerceOrder;
import com.echomind.saas.commerce.domain.CustomerAccount;
import com.echomind.saas.commerce.domain.CustomerId;
import com.echomind.saas.commerce.domain.Money;
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
import com.echomind.saas.support.application.ChatSupportActions;
import com.echomind.saas.ticket.application.CreateTicketCommand;
import com.echomind.saas.ticket.application.TicketCommands;
import com.echomind.saas.ticket.application.TicketCreationResult;
import com.echomind.saas.ticket.domain.Ticket;
import com.echomind.saas.ticket.domain.TicketId;
import com.echomind.saas.ticket.domain.TicketPriority;
import com.echomind.saas.ticket.domain.TicketStatus;
import com.echomind.skill.SkillManager;
import com.echomind.tool.KnowledgeToolManager;
import com.echomind.trace.ToolCallTrace;
import com.echomind.trace.RequestTraceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EchoMindControllerChatRagTest {

    private final AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
    private final IntentRecognizer intentRecognizer = mock(IntentRecognizer.class);
    private final MemoryManager memoryManager = mock(MemoryManager.class);
    private final KnowledgeToolManager legacyKnowledge = mock(KnowledgeToolManager.class);
    private final KnowledgeRetrieval knowledgeRetrieval = mock(KnowledgeRetrieval.class);
    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
    private final AnswerVerifier answerVerifier = mock(AnswerVerifier.class);
    private final CommerceQuery commerce = mock(CommerceQuery.class);
    private final TicketCommands tickets = mock(TicketCommands.class);
    private final RequestTraceStore traceStore = new RequestTraceStore();
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
            mock(PrometheusMeterRegistry.class),
            new ChatSupportActions(commerce, new CommerceAccessPolicy(), tickets, new HandoffPolicy()),
            traceStore);

    @Test
    void orderStatusReturnsTheAuthorizedCommercialRecord() {
        ActorScope actor = actorWithCustomers(Set.of("customer-001", "customer-002"));
        var customer = new CustomerId("customer-001");
        when(commerce.load(customer, actor)).thenReturn(snapshot(customer, "fixture-order-072", "PAID"));

        ChatResponse response = controller.chat(
                new ChatRequest("请查一下 customer-001 的订单，列出订单号和状态。", "chat-order", "customer-001"),
                actor);

        assertThat(response.response()).contains("fixture-order-072", "PAID");
        assertThat(response.intent()).isEqualTo("order_status");
        assertThat(response.conversationId()).isEqualTo("chat-order");
        assertThat(response.grounded()).isTrue();
        assertThat(traceStore.find(response.requestId(), actor)).hasValueSatisfying(trace ->
                assertThat(trace.toolCalls()).singleElement().satisfies(call -> {
                    assertThat(call.toolName()).isEqualTo("commerce_order_lookup");
                    assertThat(call.success()).isTrue();
                }));
        assertThat(traceStore.find(response.requestId(), new ActorScope(
                actor.userId(), new TenantId("other-tenant"), actor.roles(), actor.customerIds(), "other")))
                .isEmpty();
        verify(commerce).load(customer, actor);
        verifyNoInteractions(intentRecognizer, orchestrator, tickets);
    }

    @Test
    void namedCustomerOverridesUiDefaultAndLogisticsDoesNotInventTracking() {
        ActorScope actor = actorWithCustomers(Set.of("customer-001", "customer-002"));
        var customer = new CustomerId("customer-002");
        when(commerce.load(customer, actor)).thenReturn(snapshot(customer, "fixture-order-095", "PAID"));

        ChatResponse response = controller.chat(
                new ChatRequest("请查 customer-002 的订单 fixture-order-095 的物流", "chat-logistics", "customer-001"),
                actor);

        assertThat(response.intent()).isEqualTo("logistics");
        assertThat(response.response()).contains("fixture-order-095", "PAID", "无法确认当前配送位置");
        verify(commerce).load(customer, actor);
        verifyNoInteractions(intentRecognizer, tickets);
    }

    @Test
    void explicitHumanRequestCreatesOneTicketPerConversationWithoutModelIntent() {
        ActorScope actor = actorWithCustomers(Set.of("customer-001", "customer-002"));
        Map<String, Ticket> stored = new HashMap<>();
        when(tickets.create(any(), eq(actor))).thenAnswer(invocation -> {
            CreateTicketCommand command = invocation.getArgument(0);
            Ticket existing = stored.get(command.idempotencyKey());
            if (existing != null) {
                return new TicketCreationResult(existing, true);
            }
            Instant now = Instant.parse("2026-11-19T00:00:00Z");
            Ticket ticket = new Ticket(new TicketId("ticket-test-handoff"), command.customerId(),
                    actor.userId().value(), command.subject(), command.description(), TicketStatus.QUEUED,
                    TicketPriority.NORMAL, "support-default", null, command.conversationId(),
                    null, null, null, 0, now, now, null, null);
            stored.put(command.idempotencyKey(), ticket);
            return new TicketCreationResult(ticket, false);
        });

        ChatResponse first = controller.chat(
                new ChatRequest("我要转人工，找人工客服处理工单。", null, "customer-001"), actor);
        ChatResponse second = controller.chat(
                new ChatRequest("请转人工客服", first.conversationId(), "customer-001"), actor);

        assertThat(first.conversationId()).isNotBlank().isEqualTo(second.conversationId());
        assertThat(first.escalated()).isTrue();
        assertThat(second.escalated()).isTrue();
        assertThat(first.handoffTicketId()).isEqualTo("ticket-test-handoff").isEqualTo(second.handoffTicketId());
        assertThat(first.response()).contains("ticket-test-handoff", "QUEUED");
        assertThat(second.response()).contains("ticket-test-handoff").doesNotContain("QUEUED");
        var commands = ArgumentCaptor.forClass(CreateTicketCommand.class);
        verify(tickets, times(2)).create(commands.capture(), eq(actor));
        assertThat(commands.getAllValues()).containsOnly(commands.getValue());
        assertThat(commands.getValue().customerId()).isEqualTo("customer-001");
        assertThat(commands.getValue().conversationId()).isEqualTo(first.conversationId());
        assertThat(stored).hasSize(1);
        assertThat(traceStore.find(first.requestId(), actor)).hasValueSatisfying(trace -> {
            assertThat(trace.toolsUsed()).containsExactly("ticket_create");
            assertThat(trace.toolCalls().getFirst().cached()).isFalse();
        });
        assertThat(traceStore.find(second.requestId(), actor)).hasValueSatisfying(trace -> {
            assertThat(trace.toolsUsed()).containsExactly("ticket_create");
            assertThat(trace.toolCalls().getFirst().cached()).isTrue();
        });
        verifyNoInteractions(intentRecognizer, orchestrator, commerce);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "不要转人工", "无需转人工", "我不想转人工，请继续自助处理",
            "什么情况下会转人工", "系统显示“请转人工客服”，这是什么意思？"
    })
    void negatedAndMentionedHandoffDoesNotCreateATicket(String message) {
        stubUnrecognizedChat();

        ChatResponse response = controller.chat(new ChatRequest(message, "chat-negated"), actor());

        assertThat(response.handoffTicketId()).isNull();
        assertThat(response.escalated()).isFalse();
        verifyNoInteractions(tickets);
    }

    @Test
    void deniedCustomerCannotReadOrdersOrCreateATicket() {
        assertThatThrownBy(() -> controller.chat(
                new ChatRequest("请查 customer-other 的订单状态", null, "customer-a"), actor()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> controller.chat(
                new ChatRequest("转人工", null, "customer-other"), actor()))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(commerce, tickets, memoryManager);
    }

    @Test
    void ambiguousHandoffDoesNotGuessWhichCustomerOwnsTheTicket() {
        ActorScope actor = actorWithCustomers(Set.of("customer-001", "customer-002"));

        ChatResponse response = controller.chat(new ChatRequest("转人工", "chat-ambiguous"), actor);

        assertThat(response.routingReason()).isEqualTo("CUSTOMER_SELECTION_REQUIRED");
        assertThat(response.escalated()).isFalse();
        assertThat(traceStore.find(response.requestId(), actor)).hasValueSatisfying(trace ->
                assertThat(trace.toolCalls()).isEmpty());
        verifyNoInteractions(commerce, tickets);
    }

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

    private void stubUnrecognizedChat() {
        stubChatBase();
        when(intentRecognizer.recognize(anyString(), anyList())).thenReturn(
                new IntentResult(
                        IntentCategory.OTHER, 0.0, UrgencyLevel.LOW, "other",
                        Map.of(), "no provider", 1, Map.of()));
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
        return actorWithCustomers(Set.of("customer-a"));
    }

    private static ActorScope actorWithCustomers(Set<String> customerIds) {
        return new ActorScope(
                new UserId("customer-1"),
                new TenantId("tenant-a"),
                Set.of(ActorRole.CUSTOMER),
                customerIds,
                "chat-rag");
    }

    private static CommercialSnapshot snapshot(CustomerId customer, String orderId, String status) {
        Instant time = Instant.parse("2026-11-19T00:00:00Z");
        return new CommercialSnapshot(
                new CustomerAccount(customer, "Fixture account", "ACTIVE", null),
                List.of(new CommerceOrder(
                        orderId, "fixture-product", 1, new Money(100, "USD"), status, time, time, null)),
                Optional.empty(), List.of(), Optional.empty(), List.of(), List.of());
    }
}
