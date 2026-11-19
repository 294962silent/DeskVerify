package com.echomind.saas.api.security;

import com.echomind.agent.AgentOrchestrator;
import com.echomind.agent.AgentRequest;
import com.echomind.agent.AgentType;
import com.echomind.agent.AnswerVerifier;
import com.echomind.agent.OrchestratorResult;
import com.echomind.api.EchoMindController;
import com.echomind.api.dto.EvalRunRequest;
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
import com.echomind.saas.identity.application.ActorScopeResolver;
import com.echomind.saas.identity.application.TenantAccessDeniedException;
import com.echomind.saas.identity.domain.ActorRole;
import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.evaluation.domain.EvaluationJob;
import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.identity.domain.TenantScopedActorKey;
import com.echomind.saas.identity.domain.UserId;
import com.echomind.saas.identity.security.SaasSecurityConfiguration;
import com.echomind.saas.rag.application.KnowledgeRetrieval;
import com.echomind.skill.SkillManager;
import com.echomind.tool.KnowledgeToolManager;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EchoMindController.class)
@Import({
        AppConfig.class,
        SaasSecurityConfiguration.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        AuthenticatedActorBoundaryTest.TestActorScopeConfiguration.class
})
@TestPropertySource(properties =
        "echomind.security.jwt.hmac-secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class AuthenticatedActorBoundaryTest {

    private static final String TEST_SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String INVALID_SECRET = "YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=";
    private static final String TENANT_A_KEY = "tenant:tenant-a/user:user-1";
    private static final String TENANT_B_KEY = "tenant:tenant-b/user:user-1";

    @Autowired
    private MockMvc mvc;

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
    private EvaluationJobService evaluationJobService;

    @MockitoBean
    private SkillManager skillManager;

    @MockitoBean
    private PrometheusMeterRegistry prometheusMeterRegistry;

    @BeforeEach
    void setUpControllerDependencies() {
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
                .thenReturn(new OrchestratorResult(
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
                        1.0
                ));
        when(answerVerifier.verify(anyString(), anyString(), anyString()))
                .thenReturn(new AnswerVerifier.VerificationResult(true, false, false, "test"));
    }

    @Test
    void rejectsRequestWithoutJwtUsingStableEnvelope() throws Exception {
        mvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "request-anonymous")
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.message").value("authentication required"))
                .andExpect(jsonPath("$.requestId").value("request-anonymous"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void authenticatedActorOverridesFakeBodyIdentity() throws Exception {
        mvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + signedToken(TEST_SECRET, "user-1", "tenant-a"))
                        .content("""
                                {"message":"hello","userId":"attacker","user_id":"attacker-2","conv_id":"conv-1"}
                                """))
                .andExpect(status().isOk());

        verify(memoryManager).getContext(eq(TENANT_A_KEY), eq("conv-1"), eq("hello"));
        ArgumentCaptor<AgentRequest> requestCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(orchestrator).run(requestCaptor.capture(), anyList(), any(ActorScope.class));
        assertThat(requestCaptor.getValue().userId()).isEqualTo(TENANT_A_KEY);
    }

    @Test
    void sameUserInAnotherTenantCannotReuseConversationMemory() throws Exception {
        for (String tenantId : List.of("tenant-a", "tenant-b")) {
            mvc.perform(post("/chat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", "Bearer " + signedToken(TEST_SECRET, "user-1", tenantId))
                            .content("{\"message\":\"hello\",\"conv_id\":\"shared-conv\"}"))
                    .andExpect(status().isOk());
        }

        verify(memoryManager).getContext(eq(TENANT_A_KEY), eq("shared-conv"), eq("hello"));
        verify(memoryManager).getContext(eq(TENANT_B_KEY), eq("shared-conv"), eq("hello"));
        verify(memoryManager).addMessage(eq(TENANT_A_KEY), eq("shared-conv"), any(), eq("hello"));
        verify(memoryManager).addMessage(eq(TENANT_B_KEY), eq("shared-conv"), any(), eq("hello"));
    }

    @Test
    void rejectsInvalidJwtWithoutExposingTokenContents() throws Exception {
        String token = signedToken(INVALID_SECRET, "attacker", "tenant-a");

        String body = mvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .header("X-Request-Id", "request-invalid")
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.message").value("authentication required"))
                .andExpect(jsonPath("$.requestId").value("request-invalid"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).doesNotContain(token);
        assertThat(body).doesNotContain("JwtValidationException");
    }

    @Test
    void evaluationUsesAuthenticatedActorInsteadOfBodyIdentity() throws Exception {
        when(evaluator.run(any(EvalRunRequest.class), any(TenantScopedActorKey.class))).thenReturn(Map.of("ok", true));

        mvc.perform(post("/eval/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + signedToken(TEST_SECRET, "user-1", "tenant-a"))
                        .content("""
                                {"dialogCases":[{"question":"hello","userId":"attacker","user_id":"attacker-2"}]}
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<TenantScopedActorKey> actorKeyCaptor = ArgumentCaptor.forClass(TenantScopedActorKey.class);
        verify(evaluator).run(any(EvalRunRequest.class), actorKeyCaptor.capture());
        assertThat(actorKeyCaptor.getValue().value()).isEqualTo("tenant:tenant-a/user:user-1");
    }

    @Test
    void tenantAdminCanSubmitGoldenRagEvaluationJob() throws Exception {
        when(evaluationJobService.submitGoldenRag(
                eq("rag-support-golden-v1"), any(ActorScope.class)))
                .thenReturn(new EvaluationJob(
                        "golden-job-1",
                        new TenantId("tenant-a"),
                        new UserId("user-1"),
                        "queued",
                        "queued",
                        0,
                        "2026-11-18T00:00:00Z",
                        null,
                        null,
                        null,
                        null));

        mvc.perform(post("/eval/golden-rag/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer "
                                + signedToken(TEST_SECRET, "user-1", "tenant-a"))
                        .content("""
                                {"dataset_version":"rag-support-golden-v1"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.job_id").value("golden-job-1"))
                .andExpect(jsonPath("$.status").value("queued"));
    }

    @Test
    void customerCannotSubmitGoldenRagEvaluationJob() throws Exception {
        mvc.perform(post("/eval/golden-rag/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer "
                                + signedToken(TEST_SECRET, "customer-1", "tenant-a"))
                        .content("""
                                {"dataset_version":"rag-support-golden-v1"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void tenantAdminCanReadGoldenRagMarkdownReport() throws Exception {
        when(evaluationJobService.markdownReport(
                eq("golden-job-1"), any(ActorScope.class)))
                .thenReturn("# Golden report\n");

        mvc.perform(get("/eval/jobs/golden-job-1/report.md")
                        .header("Authorization", "Bearer "
                                + signedToken(TEST_SECRET, "user-1", "tenant-a")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/markdown"))
                .andExpect(content().string("# Golden report\n"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedClaimTokens")
    void malformedOrUntrustedClaimsReturnStableUnauthorizedEnvelope(
            String label,
            String subject,
            String tenantId) throws Exception {
        String responseBody = mvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + signedToken(TEST_SECRET, subject, tenantId))
                        .header("X-Request-Id", "request-jwt-claims")
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.message").value("authentication required"))
                .andExpect(jsonPath("$.requestId").value("request-jwt-claims"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(responseBody)
                .doesNotContain("raw membership diagnostic")
                .doesNotContain(String.valueOf(subject))
                .doesNotContain(String.valueOf(tenantId));
    }

    static Stream<Arguments> malformedClaimTokens() {
        return Stream.of(
                Arguments.of("missing-sub", null, "tenant-a"),
                Arguments.of("malformed-sub", "bad subject", "tenant-a"),
                Arguments.of("missing-tenant", "user-1", null),
                Arguments.of("malformed-tenant", "user-1", "TENANT_A"),
                Arguments.of("no-membership", "denied-user", "tenant-a"),
                Arguments.of("inactive-membership", "inactive-user", "tenant-a"),
                Arguments.of("disabled-user", "disabled-user", "tenant-a"),
                Arguments.of("disabled-tenant", "user-1", "tenant-disabled")
        );
    }

    private static String signedToken(String encodedSecret, String subject, String tenantId) throws Exception {
        byte[] secret = Base64.getDecoder().decode(encodedSecret);
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .jwtID("jwt-request-1")
                .issuer("echomind-local")
                .audience(List.of("echomind-api"))
                .issueTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plusSeconds(300)));
        if (subject != null) {
            claims.subject(subject);
        }
        if (tenantId != null) {
            claims.claim("tenant_id", tenantId);
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
                if (Set.of("denied-user", "inactive-user", "disabled-user").contains(userId.value())
                        || "tenant-disabled".equals(tenantId.value())) {
                    throw new TenantAccessDeniedException("raw membership diagnostic");
                }
                if ("customer-1".equals(userId.value())
                        && "tenant-a".equals(tenantId.value())) {
                    return new ActorScope(
                            userId,
                            tenantId,
                            Set.of(ActorRole.CUSTOMER),
                            Set.of("customer-1"),
                            requestId);
                }
                if (!"user-1".equals(userId.value())
                        || !Set.of("tenant-a", "tenant-b").contains(tenantId.value())) {
                    throw new TenantAccessDeniedException("raw membership diagnostic");
                }
                return new ActorScope(
                        userId,
                        tenantId,
                        Set.of(ActorRole.SUPPORT_AGENT, ActorRole.TENANT_ADMIN),
                        Set.of("customer-1"),
                        requestId
                );
            };
        }
    }
}
