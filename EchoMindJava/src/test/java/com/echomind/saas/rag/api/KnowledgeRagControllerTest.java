package com.echomind.saas.rag.api;

import com.echomind.config.AppConfig;
import com.echomind.saas.api.error.ApiAccessDeniedHandler;
import com.echomind.saas.api.error.ApiAuthenticationEntryPoint;
import com.echomind.saas.identity.application.ActorScopeResolver;
import com.echomind.saas.identity.application.TenantAccessDeniedException;
import com.echomind.saas.identity.domain.ActorRole;
import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.identity.security.SaasSecurityConfiguration;
import com.echomind.saas.rag.application.CreateKnowledgeDocumentCommand;
import com.echomind.saas.rag.application.KnowledgeDocumentView;
import com.echomind.saas.rag.application.KnowledgeIngestionService;
import com.echomind.saas.rag.application.KnowledgeRetrieval;
import com.echomind.saas.rag.domain.KnowledgeChunk;
import com.echomind.saas.rag.domain.KnowledgeCitation;
import com.echomind.saas.rag.domain.KnowledgeDocument;
import com.echomind.saas.rag.domain.KnowledgeDocumentVersion;
import com.echomind.saas.rag.domain.KnowledgeRetrievalResult;
import com.echomind.saas.rag.domain.KnowledgeVersionStatus;
import com.echomind.saas.rag.domain.KnowledgeVisibility;
import com.echomind.saas.rag.domain.RetrievalHit;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Set;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(KnowledgeRagController.class)
@Import({
        AppConfig.class,
        SaasSecurityConfiguration.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        KnowledgeRagControllerTest.TestActorScopeConfiguration.class
})
@TestPropertySource(properties =
        "echomind.security.jwt.hmac-secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class KnowledgeRagControllerTest {

    private static final String TEST_SECRET =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private KnowledgeIngestionService ingestion;

    @MockitoBean
    private KnowledgeRetrieval retrieval;

    @Test
    void adminCreatesDraftDocumentWithInitialIndexedVersion() throws Exception {
        KnowledgeDocument document = new KnowledgeDocument(
                "doc-1", "企业版续费政策", "ACTIVE", null, "admin-1");
        KnowledgeDocumentVersion version = new KnowledgeDocumentVersion(
                "doc-1",
                1,
                KnowledgeVersionStatus.DRAFT,
                "a".repeat(64),
                "normalized",
                3,
                "local-hash-char-ngram-v1",
                "admin-1",
                null);
        when(ingestion.create(any(), any())).thenReturn(
                new KnowledgeDocumentView(document, version));

        mvc.perform(post("/api/v1/knowledge-documents")
                        .header("Authorization", "Bearer " + token("admin-1", "tenant-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "企业版续费政策",
                                  "content": "# 续费\\n\\n续费后更新席位。",
                                  "visibility": "TENANT"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Location", "/api/v1/knowledge-documents/doc-1"))
                .andExpect(jsonPath("$.document_id").value("doc-1"))
                .andExpect(jsonPath("$.version.status").value("DRAFT"))
                .andExpect(jsonPath("$.version.chunk_count").value(3))
                .andExpect(jsonPath("$.version.embedding_model")
                        .value("local-hash-char-ngram-v1"));

        ArgumentCaptor<CreateKnowledgeDocumentCommand> command =
                ArgumentCaptor.forClass(CreateKnowledgeDocumentCommand.class);
        verify(ingestion).create(command.capture(), any());
        assertThat(command.getValue().title()).isEqualTo("企业版续费政策");
        assertThat(command.getValue().visibility()).isEqualTo(KnowledgeVisibility.TENANT);
    }

    @Test
    void customerCannotCreateOrPublishDocuments() throws Exception {
        mvc.perform(post("/api/v1/knowledge-documents")
                        .header("Authorization", "Bearer " + token("customer-1", "tenant-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Forged",
                                  "content": "Content",
                                  "visibility": "TENANT"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verifyNoInteractions(ingestion, retrieval);
    }

    @Test
    void searchReturnsHybridScoresAndStructuredCitationsWithoutEmbeddings() throws Exception {
        KnowledgeChunk chunk = new KnowledgeChunk(
                "chunk-1",
                "doc-1",
                2,
                0,
                "企业版续费政策",
                "续费后五分钟内更新席位和权益。",
                null,
                "续费规则",
                "套餐与权益 > 续费规则",
                10,
                35,
                "a".repeat(64),
                KnowledgeVisibility.TENANT,
                null,
                new double[]{1.0, 0.0});
        RetrievalHit hit = new RetrievalHit(
                chunk, 0.8, 0.9, 1, 2, 0.03, 0.72);
        KnowledgeCitation citation = new KnowledgeCitation(
                "citation-1",
                "chunk-1",
                "doc-1",
                "企业版续费政策",
                2,
                null,
                "续费规则",
                "套餐与权益 > 续费规则",
                chunk.content(),
                10,
                35,
                0.72);
        when(retrieval.search("续费后席位多久生效", 3, customerActor()))
                .thenReturn(new KnowledgeRetrievalResult(
                        "续费后席位多久生效",
                        List.of(hit),
                        List.of(citation),
                        List.of("lexical_unavailable"),
                        12));

        mvc.perform(post("/api/v1/knowledge/search")
                        .header("Authorization", "Bearer " + token("customer-1", "tenant-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "query": "续费后席位多久生效",
                                  "top_k": 3
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hits[0].chunk_id").value("chunk-1"))
                .andExpect(jsonPath("$.hits[0].lexical_rank").value(1))
                .andExpect(jsonPath("$.hits[0].vector_rank").value(2))
                .andExpect(jsonPath("$.hits[0].rerank_score").value(0.72))
                .andExpect(jsonPath("$.hits[0].embedding").doesNotExist())
                .andExpect(jsonPath("$.citations[0].citation_id").value("citation-1"))
                .andExpect(jsonPath("$.citations[0].version_number").value(2))
                .andExpect(jsonPath("$.citations[0].heading_path")
                        .value("套餐与权益 > 续费规则"))
                .andExpect(jsonPath("$.degraded_modes[0]").value("lexical_unavailable"))
                .andExpect(jsonPath("$.latency_ms").value(12));
    }

    @Test
    void invalidTopKUsesStableValidationEnvelope() throws Exception {
        mvc.perform(post("/api/v1/knowledge/search")
                        .header("Authorization", "Bearer " + token("customer-1", "tenant-a"))
                        .header("X-Request-Id", "rag-validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "query": "退款多久到账",
                                  "top_k": 21
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.requestId").value("rag-validation"))
                .andExpect(jsonPath("$.retryable").value(false));

        verifyNoInteractions(retrieval);
    }

    @Test
    void statsUsesTenantFromAuthenticatedActor() throws Exception {
        when(ingestion.publishedChunks(any())).thenReturn(17L);

        mvc.perform(get("/api/v1/knowledge/stats")
                        .queryParam("tenant_id", "tenant-b")
                        .header("Authorization", "Bearer " + token("customer-1", "tenant-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published_chunks").value(17));

        verify(ingestion).publishedChunks(customerActor());
    }

    private static ActorScope customerActor() {
        return new ActorScope(
                new com.echomind.saas.identity.domain.UserId("customer-1"),
                new TenantId("tenant-a"),
                Set.of(ActorRole.CUSTOMER),
                Set.of("customer-a"),
                "rag-request");
    }

    private static String token(String subject, String tenantId) throws Exception {
        byte[] secret = Base64.getDecoder().decode(TEST_SECRET);
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .jwtID("rag-request")
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

    @TestConfiguration(proxyBeanMethods = false)
    static class TestActorScopeConfiguration {

        @Bean
        ActorScopeResolver actorScopeResolver() {
            return (userId, tenantId, requestId) -> {
                if (!Set.of("tenant-a", "tenant-b").contains(tenantId.value())) {
                    throw new TenantAccessDeniedException("raw membership diagnostic");
                }
                return switch (userId.value()) {
                    case "admin-1" -> new ActorScope(
                            userId,
                            tenantId,
                            Set.of(ActorRole.TENANT_ADMIN),
                            Set.of(),
                            requestId);
                    case "support-1" -> new ActorScope(
                            userId,
                            tenantId,
                            Set.of(ActorRole.SUPPORT_AGENT),
                            Set.of(),
                            requestId);
                    case "customer-1" -> customerActor();
                    default -> throw new TenantAccessDeniedException(
                            "raw membership diagnostic");
                };
            };
        }
    }
}
