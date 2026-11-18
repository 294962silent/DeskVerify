package com.echomind.saas.rag.application;

import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.rag.domain.KnowledgeChunk;
import com.echomind.saas.rag.domain.KnowledgeDocument;
import com.echomind.saas.rag.domain.KnowledgeDocumentVersion;
import java.util.List;
import java.util.Optional;

public interface KnowledgeDocumentRepository {
    KnowledgeDocument insertDocument(TenantId tenantId, KnowledgeDocument document);

    Optional<KnowledgeDocument> findDocument(TenantId tenantId, String documentId);

    int nextVersion(TenantId tenantId, String documentId);

    KnowledgeDocumentVersion insertVersion(
            TenantId tenantId, KnowledgeDocumentVersion version);

    Optional<KnowledgeDocumentVersion> findVersion(
            TenantId tenantId, String documentId, int versionNumber);

    Optional<KnowledgeDocumentVersion> findVersionByChecksum(
            TenantId tenantId, String documentId, String checksum);

    List<KnowledgeDocumentVersion> listVersions(TenantId tenantId, String documentId);

    void markIndexingAndInsertChunks(
            TenantId tenantId,
            String documentId,
            int versionNumber,
            String embeddingModel,
            List<KnowledgeChunk> chunks);

    void publish(
            TenantId tenantId,
            String documentId,
            int versionNumber,
            int expectedChunkCount,
            String embeddingModel);

    List<LexicalSearchHit> lexicalSearch(
            KnowledgeSearchScope scope,
            String query,
            int limit
    );

    List<KnowledgeChunk> activeChunks(KnowledgeSearchScope scope);

    long countPublishedChunks(TenantId tenantId);
}
