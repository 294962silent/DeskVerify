package com.echomind.saas.rag.application;

import com.echomind.saas.api.error.ResourceNotFoundException;
import com.echomind.saas.identity.application.TenantAccessDeniedException;
import com.echomind.saas.identity.domain.ActorRole;
import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.rag.domain.KnowledgeDocument;
import com.echomind.saas.rag.domain.KnowledgeDocumentVersion;
import com.echomind.saas.rag.domain.KnowledgeVersionStatus;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeIngestionService {

    private final KnowledgeDocumentRepository repository;
    private final KnowledgeIndexBuilder indexBuilder;

    public KnowledgeIngestionService(
            KnowledgeDocumentRepository repository,
            KnowledgeIndexBuilder indexBuilder) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.indexBuilder = Objects.requireNonNull(indexBuilder, "indexBuilder");
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
        KnowledgeIndexBuilder.PreparedKnowledge prepared = indexBuilder.prepare(
                document.documentId(),
                document.title(),
                rawContent,
                visibility,
                customerId);
        var duplicate = repository.findVersionByChecksum(
                tenantId,
                document.documentId(),
                prepared.sourceChecksum(),
                prepared.embeddingModel());
        if (duplicate.isPresent()) {
            return duplicate.orElseThrow();
        }

        int versionNumber = repository.nextVersion(tenantId, document.documentId());
        KnowledgeIndexBuilder.IndexedKnowledge indexed =
                indexBuilder.build(prepared, versionNumber);
        KnowledgeDocumentVersion version = repository.insertVersion(
                tenantId,
                new KnowledgeDocumentVersion(
                        document.documentId(),
                        versionNumber,
                        KnowledgeVersionStatus.INDEXING,
                        indexed.sourceChecksum(),
                        indexed.normalizedText(),
                        0,
                        indexed.embeddingModel(),
                        actor.userId().value(),
                        null));
        repository.markIndexingAndInsertChunks(
                tenantId,
                document.documentId(),
                versionNumber,
                indexed.embeddingModel(),
                indexed.chunks());
        return repository.findVersion(tenantId, document.documentId(), versionNumber)
                .orElse(version);
    }

    private static void requireAdmin(ActorScope actor) {
        Objects.requireNonNull(actor, "actor");
        if (!actor.hasRole(ActorRole.TENANT_ADMIN)) {
            throw new TenantAccessDeniedException("tenant admin role is required");
        }
    }

}
