package com.echomind.saas.evaluation.golden;

import static org.assertj.core.api.Assertions.assertThat;

import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.rag.application.KnowledgeRetrieval;
import com.echomind.saas.rag.application.MarkdownAwareChunker;
import com.echomind.saas.rag.domain.KnowledgeChunk;
import com.echomind.saas.rag.domain.KnowledgeCitation;
import com.echomind.saas.rag.domain.KnowledgeRetrievalResult;
import com.echomind.saas.rag.domain.KnowledgeVisibility;
import com.echomind.saas.rag.domain.RetrievalHit;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GoldenRagEvaluatorTest {

    @Test
    void calculatesRecallMrrCitationAclAndLatencyMetrics() {
        GoldenDataset full = dataset();
        GoldenDataset subset = new GoldenDataset(
                full.datasetVersion(),
                full.schemaVersion(),
                full.tenantId(),
                full.provenance(),
                full.documents(),
                List.of(
                        full.retrievalCases().get(0),
                        full.retrievalCases().get(1),
                        new GoldenDataset.RetrievalCase(
                                "case-acl",
                                GoldenDataset.Split.HOLDOUT,
                                "ACL test",
                                GoldenDataset.ActorScope.CUSTOMER_A,
                                full.retrievalCases().get(0).expectedDocumentId(),
                                1,
                                full.retrievalCases().get(0).expectedChunkId(),
                                full.retrievalCases().get(0).expectedHeadingPath(),
                                true)),
                List.of());
        AtomicInteger calls = new AtomicInteger();
        KnowledgeRetrieval retrieval = (query, topK, actor) ->
                responseFor(subset, calls.getAndIncrement(), actor);
        GoldenRagEvaluator evaluator = new GoldenRagEvaluator(retrieval);

        GoldenRagEvaluator.Outcome outcome = evaluator.evaluate(subset);

        GoldenEvaluationReport.RetrievalMetrics all = outcome.metrics().get("ALL");
        assertThat(all.recallAt1()).isEqualTo(0.3333);
        assertThat(all.recallAt3()).isEqualTo(0.6667);
        assertThat(all.mrrAt5()).isEqualTo(0.5);
        assertThat(all.citationHitRate()).isEqualTo(0.6667);
        assertThat(all.aclViolations()).isEqualTo(1);
        assertThat(all.p50LatencyMs()).isEqualTo(20);
        assertThat(all.p95LatencyMs()).isEqualTo(30);
        assertThat(outcome.cases()).extracting(
                        GoldenEvaluationReport.RetrievalCaseResult::passed)
                .containsExactly(true, true, false);
    }

    private static KnowledgeRetrievalResult responseFor(
            GoldenDataset dataset,
            int call,
            ActorScope actor) {
        GoldenDataset.RetrievalCase evaluationCase =
                dataset.retrievalCases().get(call);
        KnowledgeChunk expected = chunk(dataset, evaluationCase.expectedChunkId());
        KnowledgeChunk distractor = chunk(
                dataset, "chunk-d08f8c833af3ef7c06fce039134031f4");
        List<KnowledgeChunk> hits = switch (call) {
            case 0 -> List.of(expected);
            case 1 -> List.of(distractor, expected);
            default -> List.of(distractor);
        };
        List<RetrievalHit> retrievalHits = java.util.stream.IntStream.range(
                        0, hits.size())
                .mapToObj(index -> new RetrievalHit(
                        hits.get(index),
                        1.0,
                        1.0,
                        index + 1,
                        index + 1,
                        0.5,
                        0.9))
                .toList();
        List<KnowledgeCitation> citations = call == 2
                ? List.of()
                : List.of(citation(expected));
        return new KnowledgeRetrievalResult(
                evaluationCase.query(),
                retrievalHits,
                citations,
                List.of(),
                call == 1 ? 20 : call == 2 ? 30 : 10);
    }

    private static KnowledgeCitation citation(KnowledgeChunk chunk) {
        return new KnowledgeCitation(
                "citation-1",
                chunk.chunkId(),
                chunk.documentId(),
                chunk.documentTitle(),
                chunk.versionNumber(),
                null,
                chunk.sectionTitle(),
                chunk.headingPath(),
                chunk.content(),
                chunk.charStart(),
                chunk.charEnd(),
                0.9);
    }

    private static KnowledgeChunk chunk(GoldenDataset dataset, String chunkId) {
        GoldenDataset.Document document = dataset.documents().stream()
                .filter(item -> item.expectedChunks().stream()
                        .anyMatch(chunk -> chunk.chunkId().equals(chunkId)))
                .findFirst()
                .orElseThrow();
        GoldenDataset.ChunkExpectation expected = document.expectedChunks().stream()
                .filter(chunk -> chunk.chunkId().equals(chunkId))
                .findFirst()
                .orElseThrow();
        return new KnowledgeChunk(
                expected.chunkId(),
                document.documentId(),
                document.version(),
                expected.chunkIndex(),
                document.title(),
                document.content(),
                null,
                expected.sectionTitle(),
                expected.headingPath(),
                0,
                document.content().length(),
                expected.checksum(),
                document.visibility(),
                document.customerId(),
                new double[]{1.0, 0.0});
    }

    private static GoldenDataset dataset() {
        ObjectMapper objectMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return new GoldenDatasetLoader(
                objectMapper, new MarkdownAwareChunker())
                .load("rag-support-golden-v1");
    }
}
