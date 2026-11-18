package com.echomind.saas.rag.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalHashEmbeddingProviderTest {

    private final LocalHashEmbeddingProvider embeddings = new LocalHashEmbeddingProvider();

    @Test
    void embeddingIsDeterministicAndSharedTermsIncreaseSimilarity() {
        double[] first = embeddings.embed("企业版席位和单点登录");
        double[] second = embeddings.embed("企业版席位和单点登录");
        double[] related = embeddings.embed("企业版支持单点登录吗");
        double[] unrelated = embeddings.embed("发票抬头如何修改");

        assertThat(first).isEqualTo(second).hasSize(384);
        assertThat(cosine(first, related)).isGreaterThan(cosine(first, unrelated));
    }

    private static double cosine(double[] left, double[] right) {
        double dot = 0;
        double a = 0;
        double b = 0;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            a += left[index] * left[index];
            b += right[index] * right[index];
        }
        return dot / (Math.sqrt(a) * Math.sqrt(b));
    }
}
