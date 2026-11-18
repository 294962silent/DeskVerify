package com.echomind.saas.api.security;

import com.echomind.agent.AgentOrchestrator;
import com.echomind.agent.AgentRequest;
import com.echomind.agent.AgentType;
import com.echomind.agent.AnswerVerifier;
import com.echomind.agent.OrchestratorResult;
import com.echomind.api.EchoMindController;
import com.echomind.config.AppConfig;
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
import com.echomind.saas.api.error.ApiAccessDeniedHandler;
import com.echomind.saas.api.error.ApiAuthenticationEntryPoint;
import com.echomind.saas.api.error.GlobalApiExceptionHandler;
import com.echomind.saas.identity.application.ActorScopeResolver;
import com.echomind.saas.identity.domain.ActorRole;
import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.identity.security.SaasSecurityConfiguration;
import com.echomind.saas.rag.application.KnowledgeRetrieval;
import com.echomind.skill.SkillManager;
import com.echomind.tool.KnowledgeToolManager;
import com.echomind.trace.RequestToolTrace;
import com.echomind.trace.RequestTraceStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EchoMindController.class)
@Import({
        AppConfig.class,
        SaasSecurityConfiguration.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        GlobalApiExceptionHandler.class,
        EvaluationJobService.class,
        Task6bSecurityBoundaryTest.TestActorScopeConfiguration.class
})
@TestPropertySource(properties = {
        "echomind.security.jwt.hmac-secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "echomind.eval.jobs-path=target/task-6b-eval/jobs.json"
})
class Task6bSecurityBoundaryTest {

    private static final String TEST_SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    private final RequestTraceStore traceStore = new RequestTraceStore();

    @MockitoBean
    private AgentOrchestrator orchestrator;

    @MockitoBean
    private IntentRecognizer intentRecognizer;

    @MockitoBean
    private MemoryManager memoryManager;

    @MockitoBean
    private KnowledgeToolManager knowledgeToolManager;

    @MockitoBean
    private KnowledgeRetrieval knowledgeRetrieval;

    @MockitoBean
    private KnowledgeBaseService knowledgeBaseService;

    @MockitoBean
    private AnswerVerifier answerVerifier;

    @MockitoBean
    private PerformanceMonitor performanceMonitor;

    @MockitoBean
    private EndToEndEvaluator evaluator;

    @MockitoBean
    private SkillManager skillManager;

    @MockitoBean
    private PrometheusMeterRegistry prometheusMeterRegistry;

    @BeforeEach
    void stubChatDependencies() {
        when(memoryManager.getContext(anyString(), anyString(), anyString()))
                .thenReturn(new MemoryContext(List.of(), List.of(), Map.of(), ""));
        when(intentRecognizer.recognize(anyString(), any()))
                .thenReturn(new IntentResult(
                        IntentCategory.GREETING,
                        1.0,
                        UrgencyLevel.LOW,
                        "smalltalk",
                        Map.of(),
                        "test",
                        1,
                        Map.of()
                ));
        when(orchestrator.run(any(AgentRequest.class), anyList(), any(ActorScope.class)))
                .thenReturn(orchestratorResult("request-result"));
        when(answerVerifier.verify(anyString(), anyString(), anyString()))
                .thenReturn(new AnswerVerifier.VerificationResult(true, false, false, "test"));
        when(evaluator.run(any(), any())).thenReturn(Map.of("ok", true));
        traceStore.clear();
        when(orchestrator.getToolTrace(anyString(), any(ActorScope.class)))
                .thenAnswer(invocation -> traceStore.find(
                        invocation.getArgument(0), invocation.getArgument(1)));
        when(orchestrator.getRecentToolTraces(anyInt(), any(ActorScope.class)))
                .thenAnswer(invocation -> traceStore.recent(
                        invocation.getArgument(0), invocation.getArgument(1)));
    }

    @Test
    void evaluationJobsArePrivateToTheirTenantAndUser() throws Exception {
        String firstJobId = submitEvaluation("user-a", "tenant-a");
        String secondJobId = submitEvaluation("user-b", "tenant-a");
        String thirdJobId = submitEvaluation("user-c", "tenant-b");

        String getBody = mvc.perform(get("/eval/jobs/{jobId}", firstJobId)
                        .header("Authorization", "Bearer " + token("user-a", "tenant-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job_id").value(firstJobId))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertNoOwnershipFields(getBody);

        assertNotFound(firstJobId, "user-b", "tenant-a");
        assertNotFound(firstJobId, "user-c", "tenant-b");
        assertNotFound("unknown-job", "user-a", "tenant-a");

        String body = mvc.perform(get("/eval/jobs")
                        .header("Authorization", "Bearer " + token("user-a", "tenant-a")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertNoOwnershipFields(body);
        List<Map<String, Object>> items = objectMapper.readValue(
                objectMapper.readTree(body).get("items").toString(), new TypeReference<>() { });
        List<String> jobIds = items.stream().map(item -> String.valueOf(item.get("job_id"))).toList();
        assertThat(jobIds).contains(firstJobId).doesNotContain(secondJobId, thirdJobId);
    }

    @Test
    void customerMalformedEvalBodyIsForbiddenBeforeMvcBinding() throws Exception {
        mvc.perform(post("/eval/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token("customer-a", "tenant-a"))
                        .content("{"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.message").value("access denied"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void customerMalformedKnowledgeBodyIsForbiddenBeforeMvcBinding() throws Exception {
        mvc.perform(post("/knowledge/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token("customer-a", "tenant-a"))
                        .content("{"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void unauthenticatedMalformedBodyIsRejectedBeforeMvcBinding() throws Exception {
        mvc.perform(post("/knowledge/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void adminMalformedBodyReachesMvcValidation() throws Exception {
        mvc.perform(post("/eval/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token("admin-a", "tenant-a"))
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("request validation failed"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void adminEndpointsRequireTenantAdminRole() throws Exception {
        mvc.perform(post("/skills/reload"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        mvc.perform(post("/skills/reload")
                        .header("Authorization", "Bearer " + token("customer-a", "tenant-a")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.message").value("access denied"))
                .andExpect(jsonPath("$.retryable").value(false));

        mvc.perform(post("/skills/reload")
                        .header("Authorization", "Bearer " + token("admin-a", "tenant-a")))
                .andExpect(status().isOk());
        verify(skillManager).reload();
    }

    @Test
    void jwtWithWrongIssuerIsRejectedWithTheStableAuthenticationEnvelope() throws Exception {
        assertUnauthorizedToken(token(
                "customer-a", "tenant-a", "other-issuer", List.of("echomind-api")));
    }

    @Test
    void jwtWithWrongAudienceIsRejectedWithTheStableAuthenticationEnvelope() throws Exception {
        assertUnauthorizedToken(token(
                "customer-a", "tenant-a", "echomind-local", List.of("other-audience")));
    }

    @Test
    void jwtWithoutAudienceIsRejectedWithTheStableAuthenticationEnvelope() throws Exception {
        assertUnauthorizedToken(token(
                "customer-a", "tenant-a", "echomind-local", null));
    }

    @Test
    void customerChatRemainsAuthenticatedWithoutAdminRole() throws Exception {
        mvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token("customer-a", "tenant-a"))
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void publicOperationalAndDocumentationRoutesBypassAuthentication() throws Exception {
        assertPermittedWithoutRedirect("/actuator/health");
        assertPermittedWithoutRedirect("/actuator/info");
        assertPermittedWithoutRedirect("/v3/api-docs");
        assertPermittedWithoutRedirect("/swagger-ui.html");
        assertPermittedWithoutRedirect("/swagger-ui/index.html");
    }

    @Test
    void chatMemoryKeyIncludesTenantForTheSameLocalUser() throws Exception {
        mvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token("user-1", "tenant-a"))
                        .content("{\"message\":\"hello\",\"conv_id\":\"shared-conv\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token("user-1", "tenant-b"))
                        .content("{\"message\":\"hello\",\"conv_id\":\"shared-conv\"}"))
                .andExpect(status().isOk());

        verify(memoryManager).getContext(eq("tenant:tenant-a/user:user-1"), eq("shared-conv"), eq("hello"));
        verify(memoryManager).getContext(eq("tenant:tenant-b/user:user-1"), eq("shared-conv"), eq("hello"));
    }

    @Test
    void traceLookupAndRecentRoutesEnforceTenantAndActorOwnership() throws Exception {
        traceStore.record(trace("request-a"), actor("tenant-a", "customer-a", ActorRole.CUSTOMER));
        traceStore.record(trace("request-b"), actor("tenant-a", "customer-b", ActorRole.CUSTOMER));
        traceStore.record(trace("request-c"), actor("tenant-b", "customer-c", ActorRole.CUSTOMER));

        String ownBody = mvc.perform(get("/trace/request-a")
                        .header("Authorization", "Bearer " + token("customer-a", "tenant-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.trace.request_id").value("request-a"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertNoTraceOwnershipFields(ownBody);

        assertTraceNotFound("request-b", "customer-a", "tenant-a");
        assertTraceNotFound("request-a", "customer-c", "tenant-b");
        assertTraceNotFound("unknown-trace", "customer-a", "tenant-a");

        mvc.perform(get("/trace/request-b")
                        .header("Authorization", "Bearer " + token("admin-a", "tenant-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trace.request_id").value("request-b"));
        assertTraceNotFound("request-c", "admin-a", "tenant-a");
    }

    @Test
    void recentTraceRoutesUseTheSameOwnershipFilters() throws Exception {
        traceStore.record(trace("request-a"), actor("tenant-a", "customer-a", ActorRole.CUSTOMER));
        traceStore.record(trace("request-b"), actor("tenant-a", "customer-b", ActorRole.CUSTOMER));
        traceStore.record(trace("request-c"), actor("tenant-b", "customer-c", ActorRole.CUSTOMER));

        String ownBody = mvc.perform(get("/trace/recent")
                        .header("Authorization", "Bearer " + token("customer-a", "tenant-a")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertNoTraceOwnershipFields(ownBody);
        assertThat(traceIds(ownBody)).containsExactly("request-a");

        String adminBody = mvc.perform(get("/trace/tools")
                        .header("Authorization", "Bearer " + token("admin-a", "tenant-a")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertNoTraceOwnershipFields(adminBody);
        assertThat(traceIds(adminBody)).containsExactly("request-b", "request-a");
    }

    @Test
    void sameJwtSubjectRecordsAndReadsTracesPerTenant() throws Exception {
        when(orchestrator.run(any(AgentRequest.class), anyList(), any(ActorScope.class)))
                .thenAnswer(invocation -> {
                    ActorScope scope = invocation.getArgument(2);
                    String requestId = switch (scope.tenantId().value()) {
                        case "tenant-a" -> "request-a";
                        case "tenant-b" -> "request-b";
                        default -> throw new AssertionError("unexpected tenant");
                    };
                    traceStore.record(trace(requestId), scope);
                    return orchestratorResult(requestId);
                });

        mvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token("customer-shared", "tenant-a"))
                        .content("{\"message\":\"hello\",\"conv_id\":\"conversation-a\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token("customer-shared", "tenant-b"))
                        .content("{\"message\":\"hello\",\"conv_id\":\"conversation-b\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/trace/request-a")
                        .header("Authorization", "Bearer " + token("customer-shared", "tenant-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trace.request_id").value("request-a"));
        assertTraceNotFound("request-b", "customer-shared", "tenant-a");
        mvc.perform(get("/trace/request-b")
                        .header("Authorization", "Bearer " + token("customer-shared", "tenant-b")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trace.request_id").value("request-b"));
        assertTraceNotFound("request-a", "customer-shared", "tenant-b");

        String recentA = mvc.perform(get("/trace/recent")
                        .header("Authorization", "Bearer " + token("customer-shared", "tenant-a")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String recentB = mvc.perform(get("/trace/recent")
                        .header("Authorization", "Bearer " + token("customer-shared", "tenant-b")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(traceIds(recentA)).containsExactly("request-a");
        assertThat(traceIds(recentB)).containsExactly("request-b");
    }

    private String submitEvaluation(String userId, String tenantId) throws Exception {
        String body = mvc.perform(post("/eval/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token(userId, tenantId))
                        .content("{}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertNoOwnershipFields(body);
        return objectMapper.readTree(body).get("job_id").asText();
    }

    private static void assertNoOwnershipFields(String body) {
        assertThat(body)
                .doesNotContain("\"tenant_id\"", "\"owner_user_id\"", "\"tenantId\"", "\"ownerUserId\"");
    }

    private void assertUnauthorizedToken(String token) throws Exception {
        mvc.perform(get("/trace/recent")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.message").value("authentication required"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    private static void assertNoTraceOwnershipFields(String body) {
        assertThat(body).doesNotContain(
                "\"tenant_id\"", "\"actor_user_id\"", "\"tenantId\"", "\"actorUserId\"");
    }

    private void assertTraceNotFound(String requestId, String userId, String tenantId) throws Exception {
        mvc.perform(get("/trace/{requestId}", requestId)
                        .header("Authorization", "Bearer " + token(userId, tenantId)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("resource not found"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    private List<String> traceIds(String body) throws Exception {
        return objectMapper.readTree(body).get("items").findValuesAsText("request_id");
    }

    private static RequestToolTrace trace(String requestId) {
        return new RequestToolTrace(
                requestId,
                "2026-11-18T00:00:00Z",
                "chat",
                "tenant-qualified-user",
                "conversation-1",
                "greeting",
                "smalltalk",
                "general",
                "general",
                List.of(),
                List.of(),
                List.of(),
                false,
                false,
                1);
    }

    private static OrchestratorResult orchestratorResult(String requestId) {
        return new OrchestratorResult(
                requestId,
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

    private static ActorScope actor(String tenantId, String userId, ActorRole role) {
        return new ActorScope(
                new com.echomind.saas.identity.domain.UserId(userId),
                new com.echomind.saas.identity.domain.TenantId(tenantId),
                Set.of(role),
                Set.of(),
                "trace-test");
    }

    private void assertNotFound(String jobId, String userId, String tenantId) throws Exception {
        mvc.perform(get("/eval/jobs/{jobId}", jobId)
                        .header("Authorization", "Bearer " + token(userId, tenantId)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("resource not found"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    private void assertPermittedWithoutRedirect(String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().isNotFound());
    }

    private static String token(String subject, String tenantId) throws Exception {
        return token(subject, tenantId, "echomind-local", List.of("echomind-api"));
    }

    private static String token(
            String subject,
            String tenantId,
            String issuer,
            List<String> audience) throws Exception {
        byte[] secret = Base64.getDecoder().decode(TEST_SECRET);
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .claim("tenant_id", tenantId)
                .jwtID("task-6b-" + subject + "-" + tenantId)
                .issuer(issuer)
                .issueTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plusSeconds(300)));
        if (audience != null) {
            claims.audience(audience);
        }
        SignedJWT jwt = new SignedJWT(
                new JWSHeader(JWSAlgorithm.HS256),
                claims.build()
        );
        jwt.sign(new MACSigner(secret));
        return jwt.serialize();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestActorScopeConfiguration {
        @Bean
        ActorScopeResolver actorScopeResolver() {
            return (userId, tenantId, requestId) -> {
                if (!Set.of("tenant-a", "tenant-b").contains(tenantId.value())) {
                    throw new IllegalArgumentException("unexpected tenant");
                }
                Set<ActorRole> roles = userId.value().startsWith("customer")
                        ? Set.of(ActorRole.CUSTOMER)
                        : Set.of(ActorRole.TENANT_ADMIN);
                return new ActorScope(userId, tenantId, roles, Set.of(), requestId);
            };
        }
    }
}
