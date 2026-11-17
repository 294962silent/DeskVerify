package com.echomind.saas.api.security;

import com.echomind.agent.AgentOrchestrator;
import com.echomind.agent.AgentRequest;
import com.echomind.agent.AgentType;
import com.echomind.agent.AnswerVerifier;
import com.echomind.agent.OrchestratorResult;
import com.echomind.api.EchoMindController;
import com.echomind.api.dto.EvalRunRequest;
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
import com.echomind.saas.identity.domain.ActorRole;
import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.identity.domain.UserId;
import com.echomind.saas.identity.security.SaasSecurityConfiguration;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EchoMindController.class)
@Import({
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
        when(memoryManager.getContext(eq("user-1"), anyString(), anyString()))
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
        when(orchestrator.run(any(AgentRequest.class), anyList()))
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
                        .header("Authorization", "Bearer " + signedToken(TEST_SECRET, "user-1"))
                        .content("""
                                {"message":"hello","userId":"attacker","user_id":"attacker-2","conv_id":"conv-1"}
                                """))
                .andExpect(status().isOk());

        verify(memoryManager).getContext(eq("user-1"), eq("conv-1"), eq("hello"));
        ArgumentCaptor<AgentRequest> requestCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(orchestrator).run(requestCaptor.capture(), anyList());
        assertThat(requestCaptor.getValue().userId()).isEqualTo("user-1");
    }

    @Test
    void rejectsInvalidJwtWithoutExposingTokenContents() throws Exception {
        String token = signedToken(INVALID_SECRET, "attacker");

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
        when(evaluator.run(any(EvalRunRequest.class), any(UserId.class))).thenReturn(Map.of("ok", true));

        mvc.perform(post("/eval/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + signedToken(TEST_SECRET, "user-1"))
                        .content("""
                                {"dialogCases":[{"question":"hello","userId":"attacker","user_id":"attacker-2"}]}
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<UserId> userCaptor = ArgumentCaptor.forClass(UserId.class);
        verify(evaluator).run(any(EvalRunRequest.class), userCaptor.capture());
        assertThat(userCaptor.getValue().value()).isEqualTo("user-1");
    }

    private static String signedToken(String encodedSecret, String subject) throws Exception {
        byte[] secret = Base64.getDecoder().decode(encodedSecret);
        Instant now = Instant.now();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader(JWSAlgorithm.HS256),
                new JWTClaimsSet.Builder()
                        .subject(subject)
                        .claim("tenant_id", "tenant-a")
                        .jwtID("jwt-request-1")
                        .issueTime(Date.from(now.minusSeconds(5)))
                        .expirationTime(Date.from(now.plusSeconds(300)))
                        .build()
        );
        jwt.sign(new MACSigner(secret));
        return jwt.serialize();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestActorScopeConfiguration {
        @Bean
        ActorScopeResolver actorScopeResolver() {
            return (userId, tenantId, requestId) -> {
                if (!"user-1".equals(userId.value()) || !"tenant-a".equals(tenantId.value())) {
                    throw new IllegalArgumentException("unexpected actor");
                }
                return new ActorScope(
                        userId,
                        tenantId,
                        Set.of(ActorRole.SUPPORT_AGENT),
                        Set.of("customer-1"),
                        requestId
                );
            };
        }
    }
}
