package com.echomind.saas.rag.application;

import com.echomind.saas.api.error.ResourceNotFoundException;
import com.echomind.saas.identity.application.TenantAccessDeniedException;
import com.echomind.saas.identity.domain.ActorRole;
import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.rag.domain.KnowledgeChunk;
import com.echomind.saas.rag.domain.KnowledgeDocument;
import com.echomind.saas.rag.domain.KnowledgeDocumentVersion;
import com.echomind.saas.rag.domain.KnowledgeVersionStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeIngestionService {

    private final KnowledgeDocumentRepository repository;
    private final DocumentChunker chunker;
    private final EmbeddingProvider embeddings;

    public KnowledgeIngestionService(
            KnowledgeDocumentRepository repository,
            DocumentChunker chunker,
            EmbeddingProvider embeddings) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.chunker = Objects.requireNonNull(chunker, "chunker");
        this.embeddings = Objects.requireNonNull(embeddings, "embeddings");
    }

    @Transactional
    public KnowledgeDocumentView create(
            CreateKnowledgeDocumentCommand command, ActorScope actor) {
        requireAdmin(actor);
        String documentId = "doc-" + UUID.randomUUID();
        KnowledgeDocument document = repository.insertDocument(
                actor.tenantId(),
                new KnowledgeDocument(
                        documentId,
                        command.title(),
                        "ACTIVE",
                        null,
                        actor.userId().value()));
        KnowledgeDocumentVersion version = indexVersion(
                actor.tenantId(),
                document,
                command.content(),
                command.visibility(),
                command.customerId(),
                actor);
        return new KnowledgeDocumentView(document, version);
    }

    @Transactional
    public KnowledgeDocumentVersion addVersion(
            String documentId,
            AddKnowledgeVersionCommand command,
            ActorScope actor) {
        requireAdmin(actor);
        KnowledgeDocument document = repository.findDocument(actor.tenantId(), documentId)
                .orElseThrow(ResourceNotFoundException::new);
        return indexVersion(
                actor.tenantId(),
                document,
                command.content(),
                command.visibility(),
                command.customerId(),
                actor);
    }

    @Transactional
    public KnowledgeDocumentVersion publish(
            String documentId,
            PublishKnowledgeVersionCommand command,
            ActorScope actor) {
        requireAdmin(actor);
        repository.findDocument(actor.tenantId(), documentId)
                .orElseThrow(ResourceNotFoundException::new);
        KnowledgeDocumentVersion version = repository.findVersion(
                        actor.tenantId(), documentId, command.versionNumber())
                .orElseThrow(ResourceNotFoundException::new);
        repository.publish(
                actor.tenantId(),
                documentId,
                version.versionNumber(),
                version.chunkCount(),
                version.embeddingModel());
        return repository.findVersion(
                        actor.tenantId(), documentId, version.versionNumber())
                .orElseThrow(ResourceNotFoundException::new);
    }

    public List<KnowledgeDocumentVersion> versions(String documentId, ActorScope actor) {
        requireAdmin(actor);
        repository.findDocument(actor.tenantId(), documentId)
                .orElseThrow(ResourceNotFoundException::new);
        return repository.listVersions(actor.tenantId(), documentId);
    }

    public long publishedChunks(ActorScope actor) {
        return repository.countPublishedChunks(actor.tenantId());
    }

    private KnowledgeDocumentVersion indexVersion(
            TenantId tenantId,
            KnowledgeDocument document,
            String rawContent,
            com.echomind.saas.rag.domain.KnowledgeVisibility visibility,
            String customerId,
            ActorScope actor) {
        String normalized = normalize(rawContent);
        String checksum = sha256(normalized);
        var duplicate = repository.findVersionByChecksum(
                tenantId, document.documentId(), checksum);
        if (duplicate.isPresent()) {
            return duplicate.orElseThrow();
        }

        int versionNumber = repository.nextVersion(tenantId, document.documentId());
        KnowledgeDocumentVersion version = repository.insertVersion(
                tenantId,
                new KnowledgeDocumentVersion(
                        document.documentId(),
                        versionNumber,
                        KnowledgeVersionStatus.INDEXING,
                        checksum,
                        normalized,
                        0,
                        embeddings.modelName(),
                        actor.userId().value(),
                        null));
        List<KnowledgeChunk> chunks = chunker.chunk(normalized).stream()
                .map(chunk -> new KnowledgeChunk(
                        chunkId(document.documentId(), versionNumber, chunk.chunkIndex()),
                        document.documentId(),
                        versionNumber,
                        chunk.chunkIndex(),
                        document.title(),
                        chunk.content(),
                        chunk.pageNumber(),
                        chunk.sectionTitle(),
                        chunk.headingPath(),
                        chunk.charStart(),
                        chunk.charEnd(),
                        chunk.checksum(),
                        visibility,
                        visibility == com.echomind.saas.rag.domain.KnowledgeVisibility.CUSTOMER
                                ? customerId : null,
                        embeddings.embed(chunk.content())))
                .toList();
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("document produced no indexable chunks");
        }
        repository.markIndexingAndInsertChunks(
                tenantId,
                document.documentId(),
                versionNumber,
                embeddings.modelName(),
                chunks);
        return repository.findVersion(tenantId, document.documentId(), versionNumber)
                .orElse(version);
    }

    private static String normalize(String raw) {
        String value = raw.replace("\r\n", "\n").replace('\r', '\n');
        value = value.replaceAll("[\\t\\x0B\\f]+", " ");
        value = value.lines()
                .map(String::stripTrailing)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        return value.replaceAll("\n{3,}", "\n\n").strip();
    }

    private static String chunkId(String documentId, int version, int index) {
        return "chunk-" + sha256(documentId + ":" + version + ":" + index).substring(0, 32);
    }

    private static void requireAdmin(ActorScope actor) {
        Objects.requireNonNull(actor, "actor");
        if (!actor.hasRole(ActorRole.TENANT_ADMIN)) {
            throw new TenantAccessDeniedException("tenant admin role is required");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
