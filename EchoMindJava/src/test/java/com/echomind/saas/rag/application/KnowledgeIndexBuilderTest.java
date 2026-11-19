package com.echomind.saas.rag.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.echomind.saas.rag.domain.KnowledgeVisibility;
import org.junit.jupiter.api.Test;

class KnowledgeIndexBuilderTest {

    @Test
    void buildsFixedChunkIdAndChecksumForGoldenDocument() {
        LocalHashEmbeddingProvider embeddings = new LocalHashEmbeddingProvider();
        KnowledgeIndexBuilder builder = new KnowledgeIndexBuilder(
                new MarkdownAwareChunker(), embeddings);

        KnowledgeIndexBuilder.PreparedKnowledge prepared = builder.prepare(
                "golden-doc-refund-policy",
                "退款政策",
                "# 退款政策\n\n标准订阅退款申请需在购买后七个自然日内提交。"
                        + "已使用超过二十个席位的订单需要人工审核。",
                KnowledgeVisibility.TENANT,
                null);
        KnowledgeIndexBuilder.IndexedKnowledge indexed = builder.build(prepared, 1);

        assertThat(indexed.sourceChecksum())
                .isEqualTo("25fbfa37e43b133f5fbd90bc2bacb47b703c40b8affb8003a8532bc146846a7b");
        assertThat(indexed.chunks()).singleElement().satisfies(chunk -> {
            assertThat(chunk.chunkId())
                    .isEqualTo("chunk-2e8259f26c55c93d5de365509f7b4eab");
            assertThat(chunk.checksum()).isEqualTo(indexed.sourceChecksum());
            assertThat(chunk.embedding()).hasSize(384);
        });
    }
}
