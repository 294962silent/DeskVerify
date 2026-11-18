package com.echomind.saas.rag;

import com.echomind.agent.AgentOrchestrator;
import com.echomind.agent.AgentRequest;
import com.echomind.agent.AgentType;
import com.echomind.agent.AnswerVerifier;
import com.echomind.agent.OrchestratorResult;
import com.echomind.evaluation.EndToEndEvaluator;
import com.echomind.evaluation.EvaluationJobService;
import com.echomind.intent.IntentRecognizer;
import com.echomind.intent.IntentCategory;
import com.echomind.intent.IntentResult;
import com.echomind.intent.UrgencyLevel;
import com.echomind.knowledge.KnowledgeBaseService;
import com.echomind.llm.LlmGateway;
import com.echomind.memory.MemoryManager;
import com.echomind.memory.MemoryContext;
import com.echomind.monitor.PerformanceMonitor;
import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.testsupport.MySqlTestContainers;
import com.echomind.saas.rag.application.KnowledgeDocumentRepository;
import com.echomind.saas.rag.domain.KnowledgeChunk;
import com.echomind.saas.rag.domain.KnowledgeDocument;
import com.echomind.saas.rag.domain.KnowledgeDocumentVersion;
import com.echomind.saas.rag.domain.KnowledgeVersionStatus;
import com.echomind.saas.rag.domain.KnowledgeVisibility;
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
import java.util.Map;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class KnowledgeRagEndToEndTest {

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
        registry.add("echomind.storage.data-dir", () -> "target/rag-e2e/data");
        registry.add("echomind.storage.knowledge-path", () -> "target/rag-e2e/data/knowledge.json");
        registry.add("echomind.storage.memory-path", () -> "target/rag-e2e/data/memory.json");
        registry.add("echomind.eval.jobs-path", () -> "target/rag-e2e/data/eval-jobs.json");
        registry.add("echomind.skills.root-dir", () -> "target/rag-e2e/skills");
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
    private KnowledgeDocumentRepository knowledgeRepository;

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
        jdbc.update("delete from knowledge_chunk");
        jdbc.update("delete from knowledge_document_version");
        jdbc.update("delete from knowledge_document");
        jdbc.update("delete from customer_membership");
        jdbc.update("delete from role_binding");
        jdbc.update("delete from tenant_membership");
        jdbc.update("delete from customer_account");
        jdbc.update("delete from app_user");
        jdbc.update("delete from tenant");

        jdbc.update("insert into tenant(id, display_name, status) values('tenant-a','A','ACTIVE')");
        jdbc.update("insert into app_user(id, display_name, status) values('admin-1','Admin','ACTIVE')");
        jdbc.update("insert into app_user(id, display_name, status) values('customer-1','Customer','ACTIVE')");
        jdbc.update("""
                insert into tenant_membership(tenant_id, user_id, status)
                values ('tenant-a','admin-1','ACTIVE'),
                       ('tenant-a','customer-1','ACTIVE')
                """);
        jdbc.update("""
                insert into role_binding(tenant_id, user_id, role_name)
                values ('tenant-a','admin-1','TENANT_ADMIN'),
                       ('tenant-a','customer-1','CUSTOMER')
                """);
        jdbc.update("""
                insert into customer_membership(tenant_id, user_id, customer_id, permission)
                values ('tenant-a','customer-1','customer-a','READ')
                """);
        jdbc.update("""
                insert into customer_account(tenant_id, customer_id, display_name, status)
                values ('tenant-a','customer-a','Customer A','ACTIVE')
                """);
    }

    @Test
    void adminPublishesDocumentAndCustomerReceivesGroundedCitation() throws Exception {
        String adminToken = token("admin-1", "tenant-a", "rag-create");
        String customerToken = token("customer-1", "tenant-a", "rag-search");

        String createBody = mvc.perform(post("/api/v1/knowledge-documents")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "企业版续费与席位",
                                  "content": "# 续费规则\\n\\n企业版续费后五分钟内更新全部席位和权益。\\n\\n# 故障处理\\n\\n如果权益没有生效，请刷新会话。",
                                  "visibility": "TENANT"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        JsonNode created = objectMapper.readTree(createBody);
        String documentId = created.path("document_id").asText();
        int version = created.path("version").path("version_number").asInt();

        mvc.perform(post("/api/v1/knowledge-documents/{documentId}/publish", documentId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version_number\":%d}".formatted(version)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.chunk_count").value(2));

        mvc.perform(post("/api/v1/knowledge/search")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "query": "企业版续费后席位多久生效",
                                  "top_k": 3
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hits[0].document_id").value(documentId))
                .andExpect(jsonPath("$.hits[0].version_number").value(version))
                .andExpect(jsonPath("$.hits[0].lexical_score").isNumber())
                .andExpect(jsonPath("$.hits[0].vector_score").isNumber())
                .andExpect(jsonPath("$.hits[0].rrf_score").isNumber())
                .andExpect(jsonPath("$.hits[0].rerank_score").isNumber())
                .andExpect(jsonPath("$.citations[0].citation_id").value("citation-1"))
                .andExpect(jsonPath("$.citations[0].document_title")
                        .value("企业版续费与席位"))
                .andExpect(jsonPath("$.citations[0].heading_path")
                        .value("续费规则"));

        when(memoryManager.getContext(anyString(), anyString(), anyString()))
                .thenReturn(new MemoryContext(List.of(), List.of(), Map.of(), ""));
        when(intentRecognizer.recognize(anyString(), anyList())).thenReturn(
                new IntentResult(
                        IntentCategory.QUERY,
                        0.95,
                        UrgencyLevel.LOW,
                        "knowledge",
                        Map.of(),
                        "test",
                        1,
                        Map.of()));
        when(orchestrator.run(any(AgentRequest.class), anyList(), any()))
                .thenReturn(new OrchestratorResult(
                        "chat-rag",
                        "企业版续费后五分钟内更新席位。",
                        AgentType.GENERAL,
                        IntentCategory.QUERY,
                        false,
                        12,
                        List.of(AgentType.GENERAL),
                        AgentType.GENERAL,
                        List.of(),
                        List.of(),
                        List.of(),
                        "grounded knowledge",
                        0.9));
        when(answerVerifier.verify(anyString(), anyString(), anyString()))
                .thenReturn(new AnswerVerifier.VerificationResult(
                        true, true, false, "grounded"));

        mvc.perform(post("/chat")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "企业版续费后席位多久生效"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.knowledge_used").value(true))
                .andExpect(jsonPath("$.citations[0].citation_id").value("citation-1"))
                .andExpect(jsonPath("$.citations[0].document_title")
                        .value("企业版续费与席位"))
                .andExpect(jsonPath("$.citations[0].heading_path")
                        .value("续费规则"))
                .andExpect(jsonPath("$.retrieval_degraded_modes").isEmpty());

        assertThat(jdbc.queryForObject(
                "select count(*) from knowledge_chunk where tenant_id = 'tenant-a'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void migratesLegacyHashDocumentAndPublishesGroundedBgeVersion() throws Exception {
        String documentId = seedLegacyHashDocument();
        String adminToken = token("admin-1", "tenant-a", "legacy-migrate");
        String customerToken = token("customer-1", "tenant-a", "legacy-search");

        mvc.perform(post("/api/v1/knowledge/embedding-migrations")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "dry_run": true,
                                  "limit": 10
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dry_run").value(true))
                .andExpect(jsonPath("$.scanned").value(1))
                .andExpect(jsonPath("$.planned").value(1))
                .andExpect(jsonPath("$.migrated").value(0))
                .andExpect(jsonPath("$.remaining").value(1))
                .andExpect(jsonPath("$.items[0].status").value("PLANNED"));
        assertThat(knowledgeRepository.findDocument(
                        new TenantId("tenant-a"), documentId)
                .orElseThrow()
                .currentVersion()).isEqualTo(1);

        mvc.perform(post("/api/v1/knowledge/embedding-migrations")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "dry_run": false,
                                  "limit": 10
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanned").value(1))
                .andExpect(jsonPath("$.migrated").value(1))
                .andExpect(jsonPath("$.remaining").value(0))
                .andExpect(jsonPath("$.items[0].document_id").value(documentId))
                .andExpect(jsonPath("$.items[0].source_version_number").value(1))
                .andExpect(jsonPath("$.items[0].target_version_number").value(2))
                .andExpect(jsonPath("$.items[0].source_embedding_model")
                        .value("local-hash-char-ngram-v1"))
                .andExpect(jsonPath("$.items[0].status").value("MIGRATED"));

        assertThat(knowledgeRepository.findDocument(
                        new TenantId("tenant-a"), documentId)
                .orElseThrow()
                .currentVersion()).isEqualTo(2);
        assertThat(knowledgeRepository.findVersion(
                        new TenantId("tenant-a"), documentId, 1)
                .orElseThrow()
                .status()).isEqualTo(KnowledgeVersionStatus.RETIRED);
        assertThat(knowledgeRepository.findVersion(
                        new TenantId("tenant-a"), documentId, 2)
                .orElseThrow()
                .status()).isEqualTo(KnowledgeVersionStatus.PUBLISHED);
        assertThat(jdbc.queryForObject("""
                select json_length(embedding_json)
                from knowledge_chunk
                where tenant_id = 'tenant-a'
                  and document_id = ?
                  and version_number = 2
                """, Integer.class, documentId)).isEqualTo(512);

        mvc.perform(post("/api/v1/knowledge/embedding-migrations")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "dry_run": false,
                                  "limit": 10
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanned").value(0))
                .andExpect(jsonPath("$.migrated").value(0))
                .andExpect(jsonPath("$.remaining").value(0));

        mvc.perform(post("/api/v1/knowledge/search")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "query": "退款多久到账",
                                  "top_k": 3
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hits[0].document_id").value(documentId))
                .andExpect(jsonPath("$.hits[0].version_number").value(2))
                .andExpect(jsonPath("$.hits[0].vector_score").isNumber())
                .andExpect(jsonPath("$.citations[0].document_title")
                        .value("旧版退款政策"))
                .andExpect(jsonPath("$.degraded_modes").isEmpty());

        assertThat(jdbc.queryForObject("""
                select count(*) from knowledge_document_version
                where tenant_id = 'tenant-a' and document_id = ?
                """, Integer.class, documentId)).isEqualTo(2);
    }

    private String seedLegacyHashDocument() {
        TenantId tenantId = new TenantId("tenant-a");
        String documentId = "doc-legacy-hash";
        String content = "# 退款到账\n\n退款审核通过后五到七个工作日内到账。";
        KnowledgeDocument document = knowledgeRepository.insertDocument(
                tenantId,
                new KnowledgeDocument(
                        documentId,
                        "旧版退款政策",
                        "ACTIVE",
                        null,
                        "admin-1"));
        KnowledgeChunk chunk = new KnowledgeChunk(
                "chunk-legacy-hash",
                documentId,
                1,
                0,
                document.title(),
                content,
                null,
                "退款到账",
                "退款到账",
                0,
                content.length(),
                "a".repeat(64),
                KnowledgeVisibility.TENANT,
                null,
                new double[]{1.0, 0.0, 0.0});
        knowledgeRepository.insertVersion(
                tenantId,
                new KnowledgeDocumentVersion(
                        documentId,
                        1,
                        KnowledgeVersionStatus.INDEXING,
                        "b".repeat(64),
                        content,
                        0,
                        "local-hash-char-ngram-v1",
                        "admin-1",
                        null));
        knowledgeRepository.markIndexingAndInsertChunks(
                tenantId,
                documentId,
                1,
                "local-hash-char-ngram-v1",
                List.of(chunk));
        knowledgeRepository.publish(
                tenantId,
                documentId,
                1,
                1,
                "local-hash-char-ngram-v1");
        return document.documentId();
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
