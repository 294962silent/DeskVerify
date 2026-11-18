package com.echomind.saas.rag.application;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownAwareChunkerTest {

    private final MarkdownAwareChunker chunker = new MarkdownAwareChunker();

    @Test
    void preservesHeadingContextRangesAndOverlap() {
        String text = """
                # 套餐与权益

                企业版包含 20 个席位和单点登录。

                ## 续费规则

                %s

                ## 故障处理

                如果续费成功但权益未生效，请先刷新会话，再检查订阅状态。
                """.formatted("续费后权益应在五分钟内生效。".repeat(90));

        List<ChunkDraft> chunks = chunker.chunk(text);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.charStart()).isGreaterThanOrEqualTo(0);
            assertThat(chunk.charEnd()).isGreaterThan(chunk.charStart());
            assertThat(chunk.content()).isNotBlank();
            assertThat(chunk.checksum()).hasSize(64);
        });
        assertThat(chunks.getFirst().headingPath()).isEqualTo("套餐与权益");
        assertThat(chunks).anySatisfy(chunk ->
                assertThat(chunk.headingPath()).isEqualTo("套餐与权益 > 续费规则"));
        assertThat(chunks.get(2).charStart()).isLessThan(chunks.get(1).charEnd());
    }

    @Test
    void chunkingIsDeterministicAndBlankInputHasNoChunks() {
        String text = "# 退款\n\n" + "审核通过后五到七个工作日到账。".repeat(80);

        assertThat(chunker.chunk(text)).isEqualTo(chunker.chunk(text));
        assertThat(chunker.chunk("   ")).isEmpty();
    }
}
