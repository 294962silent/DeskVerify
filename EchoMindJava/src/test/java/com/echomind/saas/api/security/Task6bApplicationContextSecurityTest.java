package com.echomind.saas.api.security;

import com.echomind.agent.AgentOrchestrator;
import com.echomind.agent.AgentRequest;
import com.echomind.agent.AgentType;
import com.echomind.agent.AnswerVerifier;
import com.echomind.agent.OrchestratorResult;
import com.echomind.evaluation.EndToEndEvaluator;
import com.echomind.evaluation.EvaluationJobService;
import com.echomind.intent.IntentCategory;
import com.echomind.intent.IntentRecognizer;
import com.echomind.intent.IntentResult;
import com.echomind.intent.UrgencyLevel;
import com.echomind.knowledge.KnowledgeBaseService;
import com.echomind.llm.LlmGateway;
import com.echomind.memory.MemoryContext;
import com.echomind.memory.MemoryManager;
import com.echomind.monitor.PerformanceMonitor;
import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.testsupport.MySqlTestContainers;
import com.echomind.skill.SkillManager;
import com.echomind.tool.KnowledgeToolManager;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class Task6bApplicationContextSecurityTest {

    private static final String TEST_SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Container
    static final MySQLContainer<?> MYSQL = MySqlTestContainers.create();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.profiles.active", () -> "anthropic");
        registry.add("echomind.security.jwt.hmac-secret", () -> TEST_SECRET);
        registry.add("echomind.storage.data-dir", () -> "target/task-6b-context/data");
        registry.add("echomind.storage.knowledge-path", () -> "target/task-6b-context/data/knowledge.json");
        registry.add("echomind.storage.memory-path", () -> "target/task-6b-context/data/memory.json");
        registry.add("echomind.eval.jobs-path", () -> "target/task-6b-context/data/eval-jobs.json");
        registry.add("echomind.skills.root-dir", () -> "target/task-6b-context/skills");
        registry.add("management.health.redis.enabled", () -> "false");
        registry.add("spring.autoconfigure.exclude", () -> String.join(",",
                "org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration",
                "org.springframework.ai.vectorstore.chroma.autoconfigure.ChromaVectorStoreAutoConfiguration",
                "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration",
                "org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration"));
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

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

    @MockitoBean
    private LlmGateway llmGateway;

    @BeforeEach
    void seedIdentityAndStubLegacyAgentChain() {
        jdbc.update("delete from customer_membership");
        jdbc.update("delete from role_binding");
        jdbc.update("delete from tenant_membership");
        jdbc.update("delete from app_user");
        jdbc.update("delete from tenant");

        jdbc.update("insert into tenant(id, display_name, status) values('tenant-a','A','ACTIVE')");
        jdbc.update("insert into tenant(id, display_name, status) values('tenant-disabled','Disabled','DISABLED')");
        jdbc.update("insert into app_user(id, display_name, status) values('valid-user','Valid','ACTIVE')");
        jdbc.update("insert into app_user(id, display_name, status) values('inactive-user','Inactive','ACTIVE')");
        jdbc.update("insert into app_user(id, display_name, status) values('disabled-user','Disabled','DISABLED')");
        jdbc.update("insert into app_user(id, display_name, status) values('tenant-disabled-user','Tenant disabled','ACTIVE')");
        jdbc.update("insert into tenant_membership(tenant_id, user_id, status) values('tenant-a','valid-user','ACTIVE')");
        jdbc.update("insert into tenant_membership(tenant_id, user_id, status) values('tenant-a','inactive-user','INACTIVE')");
        jdbc.update("insert into tenant_membership(tenant_id, user_id, status) values('tenant-a','disabled-user','ACTIVE')");
        jdbc.update("insert into tenant_membership(tenant_id, user_id, status) values('tenant-disabled','tenant-disabled-user','ACTIVE')");
        jdbc.update("insert into role_binding(tenant_id, user_id, role_name) values('tenant-a','valid-user','SUPPORT_AGENT')");
        jdbc.update("insert into role_binding(tenant_id, user_id, role_name) values('tenant-a','inactive-user','TENANT_ADMIN')");
        jdbc.update("insert into role_binding(tenant_id, user_id, role_name) values('tenant-a','disabled-user','TENANT_ADMIN')");
        jdbc.update("insert into role_binding(tenant_id, user_id, role_name) values('tenant-disabled','tenant-disabled-user','TENANT_ADMIN')");

        when(memoryManager.getContext(anyString(), anyString(), anyString()))
                .thenReturn(new MemoryContext(List.of(), List.of(), Map.of(), ""));
        when(intentRecognizer.recognize(anyString(), any())).thenReturn(new IntentResult(
                IntentCategory.GREETING,
                1.0,
                UrgencyLevel.LOW,
                "smalltalk",
                Map.of(),
                "test",
                1,
                Map.of()
        ));
        when(orchestrator.run(any(AgentRequest.class), anyList(), any(ActorScope.class))).thenReturn(new OrchestratorResult(
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
    void validJwtUsesActualMembershipRepositoryAndReachesController() throws Exception {
        mvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token("valid-user", "tenant-a"))
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response").value("response"));

        verify(orchestrator).run(any(AgentRequest.class), anyList(), any(ActorScope.class));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "inactive-membership,inactive-user,tenant-a",
            "disabled-user,disabled-user,tenant-a",
            "disabled-tenant,tenant-disabled-user,tenant-disabled"
    })
    void inactiveOrDisabledDatabaseStateReturnsStableUnauthorizedEnvelope(
            String label,
            String userId,
            String tenantId) throws Exception {
        String body = mvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token(userId, tenantId))
                        .header("X-Request-Id", "context-jwt")
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.message").value("authentication required"))
                .andExpect(jsonPath("$.requestId").value("context-jwt"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).doesNotContain(userId, tenantId, "TenantAccessDeniedException");
    }

    @Test
    void actuatorHealthRemainsPublicInTheFullApplicationContext() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void swaggerUiRedirectAndTargetRemainPublicInTheFullApplicationContext() throws Exception {
        mvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "swagger-ui/index.html"));

        mvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());

        mvc.perform(get("/swagger-ui/assets/swagger-ui-bundle.js"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/javascript"));

        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servers[0].url").value("../"));
    }

    @Test
    void generatedOpenApiRequiresBearerJwtForProtectedOperations() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"))
                .andExpect(jsonPath("$.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.security[0].bearerAuth").isEmpty())
                .andExpect(jsonPath("$.paths['/api/v1/commerce/customers/{customerId}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/commerce/customers/{customerId}'].get.security").doesNotExist())
                .andExpect(jsonPath("$.paths['/health'].get").exists())
                .andExpect(jsonPath("$.paths['/health'].get.security").doesNotExist())
                .andExpect(result -> {
                    var paths = new ObjectMapper().readTree(result.getResponse().getContentAsByteArray())
                            .path("paths");
                    for (String path : List.of("/actuator/health", "/actuator/info", "/docs",
                            "/swagger-ui.html", "/swagger-ui/index.html", "/v3/api-docs")) {
                        if (paths.has(path)) {
                            var operationSecurity = paths.path(path).path("get").path("security");
                            assertThat(operationSecurity.isArray()).as("public path %s", path).isTrue();
                            assertThat(operationSecurity.size()).isZero();
                        }
                    }
                });
    }

    private static String token(String subject, String tenantId) throws Exception {
        byte[] secret = Base64.getDecoder().decode(TEST_SECRET);
        Instant now = Instant.now();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader(JWSAlgorithm.HS256),
                new JWTClaimsSet.Builder()
                        .subject(subject)
                        .claim("tenant_id", tenantId)
                        .jwtID("context-" + subject + "-" + tenantId)
                        .issuer("echomind-local")
                        .audience(List.of("echomind-api"))
                        .issueTime(Date.from(now.minusSeconds(5)))
                        .expirationTime(Date.from(now.plusSeconds(300)))
                        .build()
        );
        jwt.sign(new MACSigner(secret));
        return jwt.serialize();
    }
}
