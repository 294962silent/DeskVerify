package com.echomind.saas.rag.application;

import com.echomind.saas.identity.domain.ActorRole;
import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.identity.domain.UserId;
import com.echomind.saas.rag.domain.KnowledgeChunk;
import com.echomind.saas.rag.domain.KnowledgeRetrievalResult;
import com.echomind.saas.rag.domain.KnowledgeVisibility;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeRetrievalServiceTest {

    private final KnowledgeDocumentRepository repository =
            mock(KnowledgeDocumentRepository.class);
    private final LocalHashEmbeddingProvider embeddings = new LocalHashEmbeddingProvider();
    private final KnowledgeRetrievalService service = new KnowledgeRetrievalService(
            repository, embeddings, new HeuristicReranker());

    @Test
    void fusesLexicalAndVectorRanksAndBuildsActiveVersionCitations() {
        KnowledgeChunk lexical = chunk(
                "chunk-lexical",
                "企业版续费与席位",
                "续费规则",
                "企业版续费后五分钟内更新席位和权益。");
        KnowledgeChunk vector = chunk(
                "chunk-vector",
                "单点登录说明",
                "登录",
                "企业版支持单点登录和统一身份认证。");
        when(repository.lexicalSearch(any(), eq("企业版续费后席位多久生效"), eq(20)))
                .thenReturn(List.of(new LexicalSearchHit(lexical, 0.92)));
        when(repository.activeChunks(any(), eq(embeddings.modelName())))
                .thenReturn(List.of(lexical, vector));

        KnowledgeRetrievalResult result = service.search(
                "企业版续费后席位多久生效", 3, admin());

        assertThat(result.degradedModes()).isEmpty();
        assertThat(result.hits()).isNotEmpty();
        assertThat(result.hits().getFirst().chunk().chunkId()).isEqualTo("chunk-lexical");
        assertThat(result.citations()).hasSameSizeAs(result.hits());
        assertThat(result.citations().getFirst()).satisfies(citation -> {
            assertThat(citation.citationId()).isEqualTo("citation-1");
            assertThat(citation.documentTitle()).isEqualTo("企业版续费与席位");
            assertThat(citation.versionNumber()).isEqualTo(2);
            assertThat(citation.headingPath()).contains("续费规则");
            assertThat(citation.charEnd()).isGreaterThan(citation.charStart());
        });
    }

    @Test
    void lexicalFailureProducesDegradedVectorOnlyResult() {
        KnowledgeChunk vector = chunk(
                "chunk-vector",
                "退款到账",
                "退款",
                "退款审核通过后五到七个工作日到账。");
        when(repository.lexicalSearch(any(), any(), eq(20)))
                .thenThrow(new IllegalStateException("fulltext unavailable"));
        when(repository.activeChunks(any(), eq(embeddings.modelName())))
                .thenReturn(List.of(vector));

        KnowledgeRetrievalResult result = service.search("退款多久到账", 2, admin());

        assertThat(result.degradedModes()).contains("lexical_unavailable");
        assertThat(result.hits()).isNotEmpty();
        assertThat(result.citations()).isNotEmpty();
    }

    @Test
    void unavailableEmbeddingFallsBackToLexicalWithExplicitDegradedModes() {
        KnowledgeChunk lexical = chunk(
                "chunk-lexical",
                "退款到账",
                "退款",
                "退款审核通过后五到七个工作日到账。");
        EmbeddingProvider unavailable = new EmbeddingProvider() {
            @Override
            public String modelName() {
                return "bge-small-zh-v1.5-onnx-fp32";
            }

            @Override
            public double[] embed(String text) {
                throw new EmbeddingUnavailableException("model missing");
            }

            @Override
            public boolean available() {
                return false;
            }
        };
        KnowledgeRetrievalService fallbackService = new KnowledgeRetrievalService(
                repository, unavailable, new HeuristicReranker());
        when(repository.lexicalSearch(any(), eq("退款多久到账"), eq(20)))
                .thenReturn(List.of(new LexicalSearchHit(lexical, 0.9)));
        when(repository.activeChunks(any(), eq(unavailable.modelName())))
                .thenReturn(List.of(lexical));

        KnowledgeRetrievalResult result = fallbackService.search(
                "退款多久到账", 2, admin());

        assertThat(result.degradedModes())
                .contains("embedding_unavailable", "vector_unavailable");
        assertThat(result.hits()).isNotEmpty();
        assertThat(result.citations()).isNotEmpty();
    }

    @Test
    void oldEmbeddingModelProducesExplicitMismatchAndVectorUnavailableModes() {
        KnowledgeChunk lexical = chunk(
                "chunk-lexical",
                "退款到账",
                "退款",
                "退款审核通过后五到七个工作日到账。");
        when(repository.lexicalSearch(any(), eq("退款多久到账"), eq(20)))
                .thenReturn(List.of(new LexicalSearchHit(lexical, 0.9)));
        when(repository.activeChunks(any(), eq(embeddings.modelName())))
                .thenReturn(List.of());
        when(repository.countActiveChunksWithOtherEmbeddingModel(
                        any(), eq(embeddings.modelName())))
                .thenReturn(1L);

        KnowledgeRetrievalResult result = service.search(
                "退款多久到账", 2, admin());

        assertThat(result.degradedModes()).contains(
                "embedding_model_mismatch",
                "vector_unavailable");
        assertThat(result.hits()).isNotEmpty();
        assertThat(result.citations()).isNotEmpty();
    }

    private KnowledgeChunk chunk(
            String id, String title, String section, String content) {
        return new KnowledgeChunk(
                id,
                "doc-" + id,
                2,
                0,
                title,
                content,
                null,
                section,
                title + " > " + section,
                0,
                content.length(),
                "a".repeat(64),
                KnowledgeVisibility.TENANT,
                null,
                embeddings.embed(content));
    }

    private static ActorScope admin() {
        return new ActorScope(
                new UserId("admin-1"),
                new TenantId("tenant-a"),
                Set.of(ActorRole.TENANT_ADMIN),
                Set.of(),
                "rag-request");
    }
}
