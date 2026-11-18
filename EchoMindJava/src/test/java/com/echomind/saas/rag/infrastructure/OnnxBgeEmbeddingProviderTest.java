package com.echomind.saas.rag.infrastructure;

import com.echomind.config.EchoMindProperties;
import com.echomind.saas.rag.application.EmbeddingUnavailableException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OnnxBgeEmbeddingProviderTest {

    @Test
    void producesNormalizedDeterministic512DimensionalEmbeddings() {
        assumeModelPresent();
        EchoMindProperties properties = new EchoMindProperties();
        properties.getRag().setEmbeddingModel("bge-small-zh-v1.5-onnx-fp32");
        properties.getRag().setEmbeddingModelPath(
                "models/bge-small-zh-v1.5/model.onnx");
        properties.getRag().setTokenizerPath(
                "models/bge-small-zh-v1.5/tokenizer.json");

        try (OnnxBgeEmbeddingProvider provider =
                new OnnxBgeEmbeddingProvider(properties)) {
            assertThat(provider.available()).isTrue();

            double[] first = provider.embed("企业版续费后五分钟内更新席位和权益。");
            double[] second = provider.embed("企业版续费后五分钟内更新席位和权益。");

            assertThat(first).hasSize(512).isEqualTo(second);
            assertThat(norm(first)).isCloseTo(1.0, within(0.0001));
        }
    }

    @Test
    void semanticQueryEmbeddingRanksRelatedDocumentAboveUnrelatedDocument() {
        assumeModelPresent();
        EchoMindProperties properties = new EchoMindProperties();
        properties.getRag().setEmbeddingModelPath(
                "models/bge-small-zh-v1.5/model.onnx");
        properties.getRag().setTokenizerPath(
                "models/bge-small-zh-v1.5/tokenizer.json");

        try (OnnxBgeEmbeddingProvider provider =
                new OnnxBgeEmbeddingProvider(properties)) {
            double[] refundDocument = provider.embed(
                    "退款审核通过后五到七个工作日内退回原支付账户。");
            double[] relatedQuery = provider.embedQuery("退款多久可以到账");
            double[] unrelatedQuery = provider.embedQuery(
                    "企业版如何创建人工工单");

            assertThat(cosine(relatedQuery, refundDocument))
                    .isGreaterThan(cosine(unrelatedQuery, refundDocument));
        }
    }

    @Test
    void missingModelIsReportedAsUnavailable() {
        EchoMindProperties properties = new EchoMindProperties();
        properties.getRag().setEmbeddingModelPath(
                "models/bge-small-zh-v1.5/missing-model.onnx");
        properties.getRag().setTokenizerPath(
                "models/bge-small-zh-v1.5/tokenizer.json");

        try (OnnxBgeEmbeddingProvider provider =
                new OnnxBgeEmbeddingProvider(properties)) {
            assertThat(provider.available()).isFalse();
            assertThatThrownBy(() -> provider.embed("退款多久到账"))
                    .isInstanceOf(EmbeddingUnavailableException.class);
        }
    }

    private static void assumeModelPresent() {
        assumeTrue(Files.isRegularFile(
                Path.of("models/bge-small-zh-v1.5/model.onnx")));
        assumeTrue(Files.isRegularFile(
                Path.of("models/bge-small-zh-v1.5/tokenizer.json")));
    }

    private static double norm(double[] vector) {
        double sum = 0.0;
        for (double value : vector) {
            sum += value * value;
        }
        return Math.sqrt(sum);
    }

    private static double cosine(double[] left, double[] right) {
        double dot = 0.0;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
        }
        return dot;
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
