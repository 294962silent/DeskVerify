package com.echomind.saas.rag.application;

import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.rag.domain.KnowledgeChunk;
import com.echomind.saas.rag.domain.KnowledgeCitation;
import com.echomind.saas.rag.domain.KnowledgeRetrievalResult;
import com.echomind.saas.rag.domain.RetrievalHit;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeRetrievalService implements KnowledgeRetrieval {

    private static final int RRF_K = 60;
    private static final double MIN_EVIDENCE_SCORE = 0.08;

    private final KnowledgeDocumentRepository repository;
    private final EmbeddingProvider embeddings;
    private final Reranker reranker;

    public KnowledgeRetrievalService(
            KnowledgeDocumentRepository repository,
            EmbeddingProvider embeddings,
            Reranker reranker) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.embeddings = Objects.requireNonNull(embeddings, "embeddings");
        this.reranker = Objects.requireNonNull(reranker, "reranker");
    }

    @Override
    public KnowledgeRetrievalResult search(String query, int topK, ActorScope actor) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (topK < 1 || topK > 20) {
            throw new IllegalArgumentException("topK must be between 1 and 20");
        }
        Objects.requireNonNull(actor, "actor");
        Instant started = Instant.now();
        KnowledgeSearchScope scope = KnowledgeSearchScope.from(actor);
        int recall = Math.max(20, topK * 5);
        List<String> degraded = new ArrayList<>();

        CompletableFuture<List<LexicalSearchHit>> lexicalFuture =
                CompletableFuture.supplyAsync(
                        () -> repository.lexicalSearch(scope, query, recall));
        CompletableFuture<List<KnowledgeChunk>> vectorFuture =
                CompletableFuture.supplyAsync(
                        () -> repository.activeChunks(scope));

        List<LexicalSearchHit> lexical = List.of();
        List<KnowledgeChunk> chunks = List.of();
        try {
            lexical = lexicalFuture.join();
        } catch (CompletionException exception) {
            degraded.add("lexical_unavailable");
        }
        try {
            chunks = vectorFuture.join();
        } catch (CompletionException exception) {
            degraded.add("vector_unavailable");
        }

        List<ScoredChunk> vector = vectorScores(query, chunks, recall);
        List<RetrievalHit> fused = fuse(lexical, vector);
        if (fused.isEmpty()) {
            return new KnowledgeRetrievalResult(
                    query,
                    List.of(),
                    List.of(),
                    List.copyOf(degraded),
                    Duration.between(started, Instant.now()).toMillis());
        }

        List<RetrievalHit> reranked = reranker.rerank(
                query,
                fused.stream().limit(Math.max(topK * 4, 12)).toList(),
                topK);
        List<RetrievalHit> evidence = reranked.stream()
                .filter(hit -> hit.rerankScore() >= MIN_EVIDENCE_SCORE)
                .limit(topK)
                .toList();
        List<KnowledgeCitation> citations = new ArrayList<>();
        for (int index = 0; index < evidence.size(); index++) {
            RetrievalHit hit = evidence.get(index);
            KnowledgeChunk chunk = hit.chunk();
            citations.add(new KnowledgeCitation(
                    "citation-" + (index + 1),
                    chunk.chunkId(),
                    chunk.documentId(),
                    chunk.documentTitle(),
                    chunk.versionNumber(),
                    chunk.pageNumber(),
                    chunk.sectionTitle(),
                    chunk.headingPath(),
                    excerpt(chunk.content()),
                    chunk.charStart(),
                    chunk.charEnd(),
                    hit.rerankScore()));
        }
        return new KnowledgeRetrievalResult(
                query,
                evidence,
                List.copyOf(citations),
                List.copyOf(degraded),
                Duration.between(started, Instant.now()).toMillis());
    }

    private List<ScoredChunk> vectorScores(
            String query, List<KnowledgeChunk> chunks, int limit) {
        if (chunks.isEmpty()) {
            return List.of();
        }
        double[] queryEmbedding = embeddings.embed(query);
        return chunks.stream()
                .map(chunk -> new ScoredChunk(
                        chunk,
                        Math.max(0.0, cosine(queryEmbedding, chunk.embedding()))))
                .filter(item -> item.score() > 0.0)
                .sorted(Comparator
                        .comparingDouble(ScoredChunk::score).reversed()
                        .thenComparing(item -> item.chunk().chunkId()))
                .limit(limit)
                .toList();
    }

    private static List<RetrievalHit> fuse(
            List<LexicalSearchHit> lexical, List<ScoredChunk> vector) {
        Map<String, KnowledgeChunk> chunks = new LinkedHashMap<>();
        Map<String, Double> lexicalScores = new HashMap<>();
        Map<String, Double> vectorScores = new HashMap<>();
        Map<String, Integer> lexicalRanks = new HashMap<>();
        Map<String, Integer> vectorRanks = new HashMap<>();

        for (int index = 0; index < lexical.size(); index++) {
            LexicalSearchHit hit = lexical.get(index);
            chunks.putIfAbsent(hit.chunk().chunkId(), hit.chunk());
            lexicalScores.put(hit.chunk().chunkId(), hit.score());
            lexicalRanks.put(hit.chunk().chunkId(), index + 1);
        }
        for (int index = 0; index < vector.size(); index++) {
            ScoredChunk hit = vector.get(index);
            chunks.putIfAbsent(hit.chunk().chunkId(), hit.chunk());
            vectorScores.put(hit.chunk().chunkId(), hit.score());
            vectorRanks.put(hit.chunk().chunkId(), index + 1);
        }

        List<RetrievalHit> hits = new ArrayList<>();
        for (Map.Entry<String, KnowledgeChunk> entry : chunks.entrySet()) {
            int lexicalRank = lexicalRanks.getOrDefault(entry.getKey(), Integer.MAX_VALUE);
            int vectorRank = vectorRanks.getOrDefault(entry.getKey(), Integer.MAX_VALUE);
            double rrf = (lexicalRank == Integer.MAX_VALUE ? 0.0 : 1.0 / (RRF_K + lexicalRank))
                    + (vectorRank == Integer.MAX_VALUE ? 0.0 : 1.0 / (RRF_K + vectorRank));
            hits.add(new RetrievalHit(
                    entry.getValue(),
                    lexicalScores.getOrDefault(entry.getKey(), 0.0),
                    vectorScores.getOrDefault(entry.getKey(), 0.0),
                    lexicalRank,
                    vectorRank,
                    round(rrf),
                    0.0));
        }
        return hits.stream()
                .sorted(Comparator
                        .comparingDouble(RetrievalHit::rrfScore).reversed()
                        .thenComparing(hit -> hit.chunk().chunkId()))
                .toList();
    }

    private static double cosine(double[] left, double[] right) {
        int length = Math.min(left.length, right.length);
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int index = 0; index < length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        return leftNorm == 0.0 || rightNorm == 0.0
                ? 0.0
                : dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private static String excerpt(String content) {
        String value = content.strip();
        return value.length() <= 320 ? value : value.substring(0, 320) + "...";
    }

    private static double round(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }

    private record ScoredChunk(KnowledgeChunk chunk, double score) {
    }
}
