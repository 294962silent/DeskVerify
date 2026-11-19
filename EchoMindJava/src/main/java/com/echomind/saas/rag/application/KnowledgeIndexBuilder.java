package com.echomind.saas.rag.application;

import com.echomind.saas.rag.domain.KnowledgeChunk;
import com.echomind.saas.rag.domain.KnowledgeVisibility;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class KnowledgeIndexBuilder {

    private final DocumentChunker chunker;
    private final EmbeddingProvider embeddings;

    public KnowledgeIndexBuilder(
            DocumentChunker chunker,
            EmbeddingProvider embeddings) {
        this.chunker = Objects.requireNonNull(chunker, "chunker");
        this.embeddings = Objects.requireNonNull(embeddings, "embeddings");
    }

    public PreparedKnowledge prepare(
            String documentId,
            String title,
            String rawContent,
            KnowledgeVisibility visibility,
            String customerId) {
        String normalized = normalizeText(rawContent);
        List<ChunkDraft> drafts = List.copyOf(chunker.chunk(normalized));
        if (drafts.isEmpty()) {
            throw new IllegalArgumentException("document produced no indexable chunks");
        }
        return new PreparedKnowledge(
                documentId,
                title,
                visibility,
                visibility == KnowledgeVisibility.CUSTOMER ? customerId : null,
                normalized,
                sha256(normalized),
                embeddings.modelName(),
                drafts);
    }

    public IndexedKnowledge build(PreparedKnowledge prepared, int versionNumber) {
        List<KnowledgeChunk> chunks = prepared.drafts().stream()
                .map(chunk -> new KnowledgeChunk(
                        deterministicChunkId(
                                prepared.documentId(),
                                versionNumber,
                                chunk.chunkIndex()),
                        prepared.documentId(),
                        versionNumber,
                        chunk.chunkIndex(),
                        prepared.title(),
                        chunk.content(),
                        chunk.pageNumber(),
                        chunk.sectionTitle(),
                        chunk.headingPath(),
                        chunk.charStart(),
                        chunk.charEnd(),
                        chunk.checksum(),
                        prepared.visibility(),
                        prepared.customerId(),
                        embeddings.embed(chunk.content())))
                .toList();
        return new IndexedKnowledge(
                prepared.documentId(),
                versionNumber,
                prepared.normalizedText(),
                prepared.sourceChecksum(),
                prepared.embeddingModel(),
                chunks);
    }

    public static String normalizeText(String raw) {
        String value = Objects.requireNonNull(raw, "raw")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f]+", " ");
        value = value.lines()
                .map(String::stripTrailing)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        return value.replaceAll("\n{3,}", "\n\n").strip();
    }

    public static String deterministicChunkId(
            String documentId,
            int version,
            int index) {
        return "chunk-" + sha256(documentId + ":" + version + ":" + index)
                .substring(0, 32);
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record PreparedKnowledge(
            String documentId,
            String title,
            KnowledgeVisibility visibility,
            String customerId,
            String normalizedText,
            String sourceChecksum,
            String embeddingModel,
            List<ChunkDraft> drafts
    ) {
        public PreparedKnowledge {
            drafts = List.copyOf(Objects.requireNonNull(drafts, "drafts"));
        }
    }

    public record IndexedKnowledge(
            String documentId,
            int versionNumber,
            String normalizedText,
            String sourceChecksum,
            String embeddingModel,
            List<KnowledgeChunk> chunks
    ) {
        public IndexedKnowledge {
            chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
        }
    }
}
