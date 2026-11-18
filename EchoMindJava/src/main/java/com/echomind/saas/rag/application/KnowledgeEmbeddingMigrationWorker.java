package com.echomind.saas.rag.application;

import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.rag.domain.KnowledgeDocument;
import com.echomind.saas.rag.domain.KnowledgeDocumentVersion;
import com.echomind.saas.rag.domain.KnowledgeVersionStatus;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeEmbeddingMigrationWorker {

    private final KnowledgeDocumentRepository repository;
    private final KnowledgeIngestionService ingestion;
    private final EmbeddingProvider embeddings;

    public KnowledgeEmbeddingMigrationWorker(
            KnowledgeDocumentRepository repository,
            KnowledgeIngestionService ingestion,
            EmbeddingProvider embeddings) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.ingestion = Objects.requireNonNull(ingestion, "ingestion");
        this.embeddings = Objects.requireNonNull(embeddings, "embeddings");
    }

    @Transactional
    public KnowledgeEmbeddingMigrationItem migrate(
            KnowledgeEmbeddingMigrationCandidate candidate,
            ActorScope actor) {
        KnowledgeDocument document = repository.findDocumentForUpdate(
                        actor.tenantId(), candidate.documentId())
                .orElseThrow(() -> new IllegalStateException(
                        "knowledge document disappeared during migration"));
        if (!Objects.equals(document.currentVersion(), candidate.versionNumber())) {
            return skipped(
                    candidate,
                    null,
                    "document current version changed before migration");
        }

        KnowledgeDocumentVersion source = repository.findVersion(
                        actor.tenantId(),
                        candidate.documentId(),
                        candidate.versionNumber())
                .orElseThrow(() -> new IllegalStateException(
                        "source knowledge version disappeared"));
        String activeModel = embeddings.modelName();
        if (source.status() != KnowledgeVersionStatus.PUBLISHED
                || source.embeddingModel().equals(activeModel)) {
            return skipped(candidate, null, "source version is no longer stale");
        }

        KnowledgeChunkAclSummary sourceAcl = requireSingleAcl(
                repository.versionChunkAclSummaries(
                        actor.tenantId(),
                        source.documentId(),
                        source.versionNumber()),
                source.chunkCount(),
                "source");
        KnowledgeDocumentVersion target = ingestion.addVersion(
                document.documentId(),
                new AddKnowledgeVersionCommand(
                        source.normalizedText(),
                        sourceAcl.visibility(),
                        sourceAcl.customerId()),
                actor);
        if (!target.embeddingModel().equals(activeModel)
                || target.versionNumber() == source.versionNumber()) {
            throw new IllegalStateException(
                    "migration did not produce a distinct target-model version");
        }
        if (target.status() != KnowledgeVersionStatus.DRAFT) {
            throw new IllegalStateException(
                    "target embedding version is not publishable: " + target.status());
        }

        KnowledgeChunkAclSummary targetAcl = requireSingleAcl(
                repository.versionChunkAclSummaries(
                        actor.tenantId(),
                        target.documentId(),
                        target.versionNumber()),
                target.chunkCount(),
                "target");
        if (targetAcl.visibility() != sourceAcl.visibility()
                || !Objects.equals(targetAcl.customerId(), sourceAcl.customerId())) {
            throw new IllegalStateException(
                    "target embedding version ACL differs from source");
        }

        KnowledgeDocumentVersion published = ingestion.publish(
                document.documentId(),
                new PublishKnowledgeVersionCommand(target.versionNumber()),
                actor);
        if (published.status() != KnowledgeVersionStatus.PUBLISHED
                || !published.embeddingModel().equals(activeModel)) {
            throw new IllegalStateException(
                    "target embedding version publication verification failed");
        }
        return new KnowledgeEmbeddingMigrationItem(
                source.documentId(),
                source.versionNumber(),
                source.embeddingModel(),
                published.versionNumber(),
                KnowledgeEmbeddingMigrationStatus.MIGRATED,
                "reindexed and published");
    }

    private static KnowledgeChunkAclSummary requireSingleAcl(
            List<KnowledgeChunkAclSummary> summaries,
            int expectedChunkCount,
            String stage) {
        if (summaries.size() != 1) {
            throw new IllegalStateException(
                    stage + " version must have exactly one ACL scope");
        }
        KnowledgeChunkAclSummary summary = summaries.getFirst();
        if (summary.chunkCount() != expectedChunkCount || expectedChunkCount == 0) {
            throw new IllegalStateException(
                    stage + " version chunk count verification failed");
        }
        return summary;
    }

    private static KnowledgeEmbeddingMigrationItem skipped(
            KnowledgeEmbeddingMigrationCandidate candidate,
            Integer targetVersionNumber,
            String message) {
        return new KnowledgeEmbeddingMigrationItem(
                candidate.documentId(),
                candidate.versionNumber(),
                candidate.embeddingModel(),
                targetVersionNumber,
                KnowledgeEmbeddingMigrationStatus.SKIPPED,
                message);
    }
}
