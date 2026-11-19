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

    Optional<KnowledgeDocument> findDocumentForUpdate(
            TenantId tenantId, String documentId);

    int nextVersion(TenantId tenantId, String documentId);

    KnowledgeDocumentVersion insertVersion(
            TenantId tenantId, KnowledgeDocumentVersion version);

    Optional<KnowledgeDocumentVersion> findVersion(
            TenantId tenantId, String documentId, int versionNumber);

    Optional<KnowledgeDocumentVersion> findVersionByChecksum(
            TenantId tenantId,
            String documentId,
            String checksum,
            String embeddingModel);

    List<KnowledgeDocumentVersion> listVersions(TenantId tenantId, String documentId);

    List<KnowledgeEmbeddingMigrationCandidate> staleEmbeddingCandidates(
            TenantId tenantId,
            String embeddingModel,
            String afterDocumentId,
            int limit);

    long countStalePublishedVersions(TenantId tenantId, String embeddingModel);

    List<KnowledgeChunkAclSummary> versionChunkAclSummaries(
            TenantId tenantId,
            String documentId,
            int versionNumber);

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

    List<KnowledgeChunk> versionChunks(
            TenantId tenantId,
            String documentId,
            int versionNumber);

    List<LexicalSearchHit> lexicalSearch(
            KnowledgeSearchScope scope,
            String query,
            int limit
    );

    List<KnowledgeChunk> activeChunks(
            KnowledgeSearchScope scope,
            String embeddingModel
    );

    long countActiveChunksWithOtherEmbeddingModel(
            KnowledgeSearchScope scope,
            String embeddingModel
    );

    long countPublishedChunks(TenantId tenantId);
}
