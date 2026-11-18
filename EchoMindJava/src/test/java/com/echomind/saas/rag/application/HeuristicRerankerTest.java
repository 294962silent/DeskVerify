package com.echomind.saas.rag.application;

import com.echomind.saas.rag.domain.KnowledgeChunk;
import com.echomind.saas.rag.domain.KnowledgeVisibility;
import com.echomind.saas.rag.domain.RetrievalHit;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeuristicRerankerTest {

    private final HeuristicReranker reranker = new HeuristicReranker();

    @Test
    void exactTitleAndContentMatchOutranksAWeakVectorOnlyHit() {
        RetrievalHit exact = hit(
                "chunk-exact",
                "企业版续费与席位",
                "续费规则",
                "企业版续费后五分钟内更新全部席位。",
                0.2,
                0.4,
                1,
                Integer.MAX_VALUE,
                0.016);
        RetrievalHit weak = hit(
                "chunk-weak",
                "发票说明",
                "发票",
                "企业版发票可以在控制台下载。",
                0.0,
                0.9,
                Integer.MAX_VALUE,
                1,
                0.016);

        List<RetrievalHit> ranked = reranker.rerank(
                "企业版续费后席位多久生效", List.of(weak, exact), 2);

        assertThat(ranked).extracting(value -> value.chunk().chunkId())
                .containsExactly("chunk-exact", "chunk-weak");
        assertThat(ranked.getFirst().rerankScore())
                .isGreaterThan(ranked.getLast().rerankScore());
    }

    private static RetrievalHit hit(
            String id,
            String title,
            String section,
            String content,
            double lexical,
            double vector,
            int lexicalRank,
            int vectorRank,
            double rrf) {
        return new RetrievalHit(
                new KnowledgeChunk(
                        id,
                        "doc-1",
                        1,
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
                        new double[0]),
                lexical,
                vector,
                lexicalRank,
                vectorRank,
                rrf,
                0.0);
    }
}
