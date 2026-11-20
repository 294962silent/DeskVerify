package com.echomind.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.echomind.agent.AgentOrchestrator;
import com.echomind.agent.AgentType;
import com.echomind.agent.AnswerVerifier;
import com.echomind.agent.GeneralAgent;
import com.echomind.agent.HandoffPolicy;
import com.echomind.agent.HandoffSignalExtractor;
import com.echomind.config.EchoMindProperties;
import com.echomind.evaluation.EndToEndEvaluator;
import com.echomind.evaluation.EvaluationJobService;
import com.echomind.intent.IntentRecognizer;
import com.echomind.knowledge.KnowledgeBaseService;
import com.echomind.llm.SpringAiLlmGateway;
import com.echomind.memory.MemoryContext;
import com.echomind.memory.MemoryManager;
import com.echomind.monitor.PerformanceMonitor;
import com.echomind.saas.commerce.application.CommerceAccessPolicy;
import com.echomind.saas.commerce.application.CommerceQuery;
import com.echomind.saas.identity.domain.ActorRole;
import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.identity.domain.UserId;
import com.echomind.saas.identity.security.ActorScopeAuthentication;
import com.echomind.saas.rag.application.KnowledgeRetrieval;
import com.echomind.saas.support.application.ChatSupportActions;
import com.echomind.saas.ticket.application.TicketCommands;
import com.echomind.skill.SkillManager;
import com.echomind.tool.KnowledgeToolManager;
import com.echomind.trace.RequestTraceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class NativeDeepSeekSupportChatTest {
    private static final String FIXTURE_PROVIDER_KEY = "fixture-native-provider";
    private static final String QUESTION = "What are the available accessibility options?";
    private static final String ANSWER = "Fixture answer from the model transport.";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ActorScope ACTOR = new ActorScope(
            new UserId("fixture-user"), new TenantId("fixture-tenant"),
            Set.of(ActorRole.CUSTOMER), Set.of("fixture-customer"), "fixture-request");

    @Test
    void ordinaryCustomerChatUsesNativeDeepSeekDefaultAndReturnsProviderAnswer() throws IOException {
        try (var provider = new LoopbackProvider()) {
            runChat(provider, FIXTURE_PROVIDER_KEY, "", ANSWER);

            assertThat(provider.requests).hasSize(3).allSatisfy(request -> {
                assertThat(request.authorized()).isTrue();
                assertThat(request.path()).isEqualTo("/native/chat/completions");
                assertThat(request.body().path("model").asText()).isEqualTo("deepseek-flash");
                assertThat(request.body().has("tools")).isFalse();
            });
            assertThat(provider.requests.get(1).body().toString()).contains(QUESTION);
        }
    }

    @Test
    void explicitlyConfiguredNativeModelIsPreserved() throws IOException {
        try (var provider = new LoopbackProvider()) {
            runChat(provider, FIXTURE_PROVIDER_KEY, "fixture-custom-model", ANSWER);

            assertThat(provider.requests).hasSize(3).allSatisfy(request ->
                    assertThat(request.body().path("model").asText()).isEqualTo("fixture-custom-model"));
        }
    }

    @Test
    void noProviderKeyKeepsAuthenticatedChatLocal() throws IOException {
        try (var provider = new LoopbackProvider()) {
            runChat(provider, "", "fixture-custom-model", null);

            assertThat(provider.requests).isEmpty();
        }
    }

    @Test
    void explicitlyBlankAnthropicKeyAlsoKeepsAuthenticatedChatLocal() throws IOException {
        try (var provider = new LoopbackProvider()) {
            runChat(provider, "anthropic", " \t ", "", null);

            assertThat(provider.requests).isEmpty();
        }
    }

    private static void runChat(LoopbackProvider provider, String key, String model, String expected) {
        runChat(provider, "deepseek", key, model, expected);
    }

    private static void runChat(LoopbackProvider provider, String profile, String key, String model, String expected) {
        boolean deepSeek = profile.equals("deepseek");
        var runner = new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(
                        deepSeek ? DeepSeekChatAutoConfiguration.class : AnthropicChatAutoConfiguration.class,
                        ToolCallingAutoConfiguration.class, SpringAiRetryAutoConfiguration.class))
                .withUserConfiguration(SpringAiLlmGateway.class, EchoMindProperties.class)
                .withPropertyValues(
                        "spring.profiles.active=" + profile,
                        "DEEPSEEK_API_KEY=" + (deepSeek ? key : ""),
                        "DEEPSEEK_BASE_URL=" + provider.baseUrl(),
                        "ANTHROPIC_API_KEY=" + (deepSeek ? "" : key),
                        "ANTHROPIC_BASE_URL=" + provider.baseUrl(),
                        "LLM_STARTUP_API_KEY=echomind-local-placeholder",
                        "spring.ai.retry.max-attempts=1");
        if (!model.isEmpty()) {
            runner = runner.withPropertyValues("DEEPSEEK_MODEL=" + model);
        }
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            EchoMindController controller = controller(context.getBean(SpringAiLlmGateway.class));
            var mvc = MockMvcBuilders.standaloneSetup(controller)
                    .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                    .build();
            SecurityContextHolder.getContext().setAuthentication(new ActorScopeAuthentication(ACTOR));
            try {
                var response = mvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON)
                                .content(JSON.writeValueAsString(Map.of("message", QUESTION))))
                        .andExpect(status().isOk());
                if (expected == null) {
                    response.andExpect(jsonPath("$.response").isNotEmpty());
                } else {
                    response.andExpect(jsonPath("$.response").value(expected));
                }
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
    }

    private static EchoMindController controller(SpringAiLlmGateway gateway) {
        var recognizer = new IntentRecognizer(gateway, JSON);
        var handoff = new HandoffPolicy();
        var traces = new RequestTraceStore();
        var router = new AgentOrchestrator(recognizer,
                Map.of(AgentType.GENERAL, List.of(new GeneralAgent(gateway, null))),
                traces, handoff, new HandoffSignalExtractor());
        MemoryManager memory = mock(MemoryManager.class);
        when(memory.getContext(anyString(), anyString(), anyString()))
                .thenReturn(new MemoryContext(List.of(), List.of(), Map.of(), ""));
        return new EchoMindController(router, recognizer, memory,
                mock(KnowledgeToolManager.class), mock(KnowledgeRetrieval.class),
                mock(KnowledgeBaseService.class), new AnswerVerifier(gateway, JSON),
                mock(PerformanceMonitor.class), mock(EndToEndEvaluator.class),
                mock(EvaluationJobService.class), mock(SkillManager.class), JSON,
                mock(PrometheusMeterRegistry.class),
                new ChatSupportActions(mock(CommerceQuery.class), new CommerceAccessPolicy(),
                        mock(TicketCommands.class), handoff), traces);
    }

    private static final class LoopbackProvider implements AutoCloseable {
        private final HttpServer server;
        private final List<Request> requests = new CopyOnWriteArrayList<>();

        private LoopbackProvider() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    var body = JSON.readTree(exchange.getRequestBody());
                    String prompt = body.path("messages").findValuesAsText("content").getLast();
                    requests.add(new Request(exchange.getRequestURI().getPath(), body,
                            ("Bearer " + FIXTURE_PROVIDER_KEY).equals(
                                    exchange.getRequestHeaders().getFirst("Authorization"))));
                    String content = prompt.contains("客服意图分析专家")
                            ? "{\"intent\":\"greeting\",\"confidence\":1.0,\"reasoning\":\"fixture\"}"
                            : prompt.contains("客服回答质量校验器")
                                    ? "{\"pass\":true,\"grounded\":false,\"need_escalation\":false}"
                                    : ANSWER;
                    byte[] response = JSON.writeValueAsString(Map.of(
                            "id", "fixture-completion", "object", "chat.completion",
                            "model", body.path("model").asText(), "choices", List.of(Map.of(
                                    "index", 0, "finish_reason", "stop", "message",
                                    Map.of("role", "assistant", "content", content)))))
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                }
            });
            server.start();
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/native";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private record Request(String path, JsonNode body, boolean authorized) {}
}
