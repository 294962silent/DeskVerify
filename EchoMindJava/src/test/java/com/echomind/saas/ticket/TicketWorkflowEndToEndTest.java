package com.echomind.saas.ticket;

import com.echomind.agent.AgentOrchestrator;
import com.echomind.agent.AnswerVerifier;
import com.echomind.evaluation.EndToEndEvaluator;
import com.echomind.evaluation.EvaluationJobService;
import com.echomind.intent.IntentRecognizer;
import com.echomind.knowledge.KnowledgeBaseService;
import com.echomind.llm.LlmGateway;
import com.echomind.memory.MemoryManager;
import com.echomind.monitor.PerformanceMonitor;
import com.echomind.saas.testsupport.MySqlTestContainers;
import com.echomind.skill.SkillManager;
import com.echomind.tool.KnowledgeToolManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class TicketWorkflowEndToEndTest {

    private static final String TEST_SECRET =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Container
    static final MySQLContainer<?> MYSQL = MySqlTestContainers.create();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.profiles.active", () -> "anthropic");
        registry.add("echomind.security.jwt.hmac-secret", () -> TEST_SECRET);
        registry.add("echomind.storage.data-dir", () -> "target/ticket-e2e/data");
        registry.add("echomind.storage.knowledge-path", () -> "target/ticket-e2e/data/knowledge.json");
        registry.add("echomind.storage.memory-path", () -> "target/ticket-e2e/data/memory.json");
        registry.add("echomind.eval.jobs-path", () -> "target/ticket-e2e/data/eval-jobs.json");
        registry.add("echomind.skills.root-dir", () -> "target/ticket-e2e/skills");
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

    @Autowired
    private ObjectMapper objectMapper;

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
    void seedIdentity() {
        jdbc.update("delete from ticket_event");
        jdbc.update("delete from ticket");
        jdbc.update("delete from outbox_event");
        jdbc.update("delete from idempotency_record");
        jdbc.update("delete from customer_membership");
        jdbc.update("delete from role_binding");
        jdbc.update("delete from tenant_membership");
        jdbc.update("delete from customer_account");
        jdbc.update("delete from app_user");
        jdbc.update("delete from tenant");

        jdbc.update("""
                insert into tenant(id, display_name, status)
                values ('tenant-a', 'Tenant A', 'ACTIVE')
                """);
        jdbc.update("""
                insert into app_user(id, display_name, status)
                values ('customer-user', 'Customer', 'ACTIVE')
                """);
        jdbc.update("""
                insert into app_user(id, display_name, status)
                values ('support-user', 'Support', 'ACTIVE')
                """);
        jdbc.update("""
                insert into tenant_membership(tenant_id, user_id, status)
                values ('tenant-a', 'customer-user', 'ACTIVE'),
                       ('tenant-a', 'support-user', 'ACTIVE')
                """);
        jdbc.update("""
                insert into role_binding(tenant_id, user_id, role_name)
                values ('tenant-a', 'customer-user', 'CUSTOMER'),
                       ('tenant-a', 'support-user', 'SUPPORT_AGENT')
                """);
        jdbc.update("""
                insert into customer_membership(
                    tenant_id, user_id, customer_id, permission
                ) values ('tenant-a', 'customer-user', 'customer-a', 'READ')
                """);
        jdbc.update("""
                insert into customer_account(
                    tenant_id, customer_id, display_name, status
                ) values ('tenant-a', 'customer-a', 'Customer A', 'ACTIVE')
                """);
    }

    @Test
    void createClaimResolveCloseReopenPreservesHistoryAndOutbox() throws Exception {
        String customerToken = token("customer-user", "tenant-a", "ticket-create");
        String supportToken = token("support-user", "tenant-a", "ticket-support");

        String createBody = mvc.perform(post("/api/v1/tickets")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "ticket-e2e-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customer_id": "customer-a",
                                  "subject": "Subscription reports are unavailable",
                                  "description": "Reports fail after renewal.",
                                  "priority": "HIGH",
                                  "commercial_refs": {
                                    "subscription_id": "subscription-a"
                                  }
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn().getResponse().getContentAsString();
        JsonNode created = objectMapper.readTree(createBody);
        String ticketId = created.path("ticket_id").asText();

        String claimBody = mvc.perform(post("/api/v1/tickets/{ticketId}/claim", ticketId)
                        .header("Authorization", "Bearer " + supportToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected_version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLAIMED"))
                .andExpect(jsonPath("$.assignee_user_id").value("support-user"))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();
        JsonNode claimed = objectMapper.readTree(claimBody);

        String resolveBody = mvc.perform(post("/api/v1/tickets/{ticketId}/resolve", ticketId)
                        .header("Authorization", "Bearer " + supportToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expected_version": %d,
                                  "resolution": "Entitlement projection repaired."
                                }
                                """.formatted(claimed.path("version").asLong())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.version").value(2))
                .andReturn().getResponse().getContentAsString();
        JsonNode resolved = objectMapper.readTree(resolveBody);

        String closeBody = mvc.perform(post("/api/v1/tickets/{ticketId}/close", ticketId)
                        .header("Authorization", "Bearer " + supportToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected_version\":%d}"
                                .formatted(resolved.path("version").asLong())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.version").value(3))
                .andReturn().getResponse().getContentAsString();
        JsonNode closed = objectMapper.readTree(closeBody);

        mvc.perform(post("/api/v1/tickets/{ticketId}/reopen", ticketId)
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expected_version": %d,
                                  "reason": "The reports page failed again."
                                }
                                """.formatted(closed.path("version").asLong())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REOPENED"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.assignee_user_id").doesNotExist());

        mvc.perform(get("/api/v1/tickets/{ticketId}/events", ticketId)
                        .header("Authorization", "Bearer " + supportToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].event_type").value("ticket.opened"))
                .andExpect(jsonPath("$[1].event_type").value("ticket.queued"))
                .andExpect(jsonPath("$[2].event_type").value("ticket.claimed"))
                .andExpect(jsonPath("$[3].event_type").value("ticket.resolved"))
                .andExpect(jsonPath("$[4].event_type").value("ticket.closed"))
                .andExpect(jsonPath("$[5].event_type").value("ticket.reopened"));

        assertThat(jdbc.queryForObject(
                "select count(*) from ticket_event where tenant_id = 'tenant-a'",
                Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForList(
                "select event_type from outbox_event order by created_at, id",
                String.class)).containsExactly(
                "ticket.created",
                "ticket.claimed",
                "ticket.resolved",
                "ticket.closed",
                "ticket.reopened");
    }

    private static String token(String subject, String tenantId, String requestId)
            throws Exception {
        byte[] secret = Base64.getDecoder().decode(TEST_SECRET);
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .jwtID(requestId)
                .issuer("echomind-local")
                .audience(List.of("echomind-api"))
                .issueTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .subject(subject)
                .claim("tenant_id", tenantId)
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(secret));
        return jwt.serialize();
    }
}
