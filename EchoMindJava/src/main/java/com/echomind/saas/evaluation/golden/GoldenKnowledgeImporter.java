package com.echomind.saas.evaluation.golden;

import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.rag.application.EmbeddingProvider;
import com.echomind.saas.rag.application.KnowledgeDocumentRepository;
import com.echomind.saas.rag.application.KnowledgeIndexBuilder;
import com.echomind.saas.rag.application.KnowledgeIndexBuilder.IndexedKnowledge;
import com.echomind.saas.rag.application.KnowledgeIndexBuilder.PreparedKnowledge;
import com.echomind.saas.rag.domain.KnowledgeChunk;
import com.echomind.saas.rag.domain.KnowledgeDocument;
import com.echomind.saas.rag.domain.KnowledgeDocumentVersion;
import com.echomind.saas.rag.domain.KnowledgeVersionStatus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public final class GoldenKnowledgeImporter {

    private static final String CREATED_BY = "golden-dataset-loader";

    private final KnowledgeDocumentRepository repository;
    private final KnowledgeIndexBuilder indexBuilder;
    private final EmbeddingProvider embeddings;

    public GoldenKnowledgeImporter(
            KnowledgeDocumentRepository repository,
            KnowledgeIndexBuilder indexBuilder,
            EmbeddingProvider embeddings) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.indexBuilder = Objects.requireNonNull(indexBuilder, "indexBuilder");
        this.embeddings = Objects.requireNonNull(embeddings, "embeddings");
    }

    @Transactional
    public void ensureImported(GoldenDataset dataset) {
        Objects.requireNonNull(dataset, "dataset");
        if (!embeddings.available()) {
            throw new GoldenDatasetException(
                    "EMBEDDING_UNAVAILABLE",
                    "BGE embedding model is unavailable");
        }
        TenantId tenantId = new TenantId(dataset.tenantId());
        for (GoldenDataset.Document expected : dataset.documents()) {
            ensureDocument(tenantId, expected);
        }
    }

    private void ensureDocument(TenantId tenantId, GoldenDataset.Document expected) {
        PreparedKnowledge prepared = indexBuilder.prepare(
                expected.documentId(),
                expected.title(),
                expected.content(),
                expected.visibility(),
                expected.customerId());
        if (!prepared.sourceChecksum().equals(expected.normalizedContentSha256())) {
            throw GoldenDatasetException.integrity(
                    "prepared checksum differs from dataset: " + expected.documentId());
        }

        KnowledgeDocument document = repository.findDocument(
                        tenantId, expected.documentId())
                .orElseGet(() -> repository.insertDocument(
                        tenantId,
                        new KnowledgeDocument(
                                expected.documentId(),
                                expected.title(),
                                "ACTIVE",
                                null,
                                CREATED_BY)));
        if (!document.title().equals(expected.title())) {
            throw importFailure(
                    "document title differs: " + expected.documentId());
        }

        KnowledgeDocumentVersion version = repository.findVersion(
                        tenantId, expected.documentId(), expected.version())
                .orElse(null);
        if (version == null) {
            version = createVersion(tenantId, expected, prepared);
        } else {
            verifyVersionIdentity(expected, prepared, version);
            version = repairDraftIfNecessary(
                    tenantId, expected, prepared, version);
        }
        verifyChunks(tenantId, expected, version);

        if (version.status() == KnowledgeVersionStatus.DRAFT
                || version.status() == KnowledgeVersionStatus.INDEXING) {
            repository.publish(
                    tenantId,
                    expected.documentId(),
                    expected.version(),
                    version.chunkCount(),
                    version.embeddingModel());
            version = repository.findVersion(
                            tenantId, expected.documentId(), expected.version())
                    .orElseThrow(() -> importFailure(
                            "published version disappeared: " + expected.documentId()));
        }
        if (version.status() != KnowledgeVersionStatus.PUBLISHED) {
            throw importFailure(
                    "version is not published: " + expected.documentId());
        }
        KnowledgeDocument current = repository.findDocument(
                        tenantId, expected.documentId())
                .orElseThrow(() -> importFailure(
                        "document disappeared: " + expected.documentId()));
        if (!Objects.equals(current.currentVersion(), expected.version())) {
            throw importFailure(
                    "document current version differs: " + expected.documentId());
        }
    }

    private KnowledgeDocumentVersion createVersion(
            TenantId tenantId,
            GoldenDataset.Document expected,
            PreparedKnowledge prepared) {
        KnowledgeDocumentVersion version = repository.insertVersion(
                tenantId,
                new KnowledgeDocumentVersion(
                        expected.documentId(),
                        expected.version(),
                        KnowledgeVersionStatus.INDEXING,
                        prepared.sourceChecksum(),
                        prepared.normalizedText(),
                        0,
                        prepared.embeddingModel(),
                        CREATED_BY,
                        null));
        IndexedKnowledge indexed = indexBuilder.build(prepared, expected.version());
        repository.markIndexingAndInsertChunks(
                tenantId,
                expected.documentId(),
                expected.version(),
                indexed.embeddingModel(),
                indexed.chunks());
        return repository.findVersion(
                        tenantId, expected.documentId(), expected.version())
                .orElse(version);
    }

    private KnowledgeDocumentVersion repairDraftIfNecessary(
            TenantId tenantId,
            GoldenDataset.Document expected,
            PreparedKnowledge prepared,
            KnowledgeDocumentVersion version) {
        List<KnowledgeChunk> chunks = repository.versionChunks(
                tenantId, expected.documentId(), expected.version());
        if (chunks.isEmpty()
                && version.status() == KnowledgeVersionStatus.INDEXING) {
            IndexedKnowledge indexed = indexBuilder.build(prepared, expected.version());
            repository.markIndexingAndInsertChunks(
                    tenantId,
                    expected.documentId(),
                    expected.version(),
                    indexed.embeddingModel(),
                    indexed.chunks());
            return repository.findVersion(
                            tenantId, expected.documentId(), expected.version())
                    .orElse(version);
        }
        return version;
    }

    private void verifyVersionIdentity(
            GoldenDataset.Document expected,
            PreparedKnowledge prepared,
            KnowledgeDocumentVersion version) {
        if (!version.sourceChecksum().equals(expected.normalizedContentSha256())
                || !version.normalizedText().equals(prepared.normalizedText())
                || !version.embeddingModel().equals(prepared.embeddingModel())) {
            throw importFailure(
                    "version identity differs: " + expected.documentId());
        }
    }

    private void verifyChunks(
            TenantId tenantId,
            GoldenDataset.Document expected,
            KnowledgeDocumentVersion version) {
        List<KnowledgeChunk> actual = repository.versionChunks(
                tenantId, expected.documentId(), expected.version());
        if (actual.size() != expected.expectedChunks().size()) {
            throw importFailure(
                    "chunk count differs: " + expected.documentId());
        }
        Map<String, GoldenDataset.ChunkExpectation> expectations = new HashMap<>();
        expected.expectedChunks().forEach(chunk -> expectations.put(chunk.chunkId(), chunk));
        for (KnowledgeChunk chunk : actual) {
            GoldenDataset.ChunkExpectation expectation = expectations.get(chunk.chunkId());
            if (expectation == null
                    || !expectation.checksum().equals(chunk.checksum())
                    || !expectation.sectionTitle().equals(chunk.sectionTitle())
                    || !expectation.headingPath().equals(chunk.headingPath())
                    || chunk.visibility() != expected.visibility()
                    || !Objects.equals(chunk.customerId(), expected.customerId())) {
                throw importFailure(
                        "chunk differs from dataset: "
                                + expected.documentId()
                                + "/"
                                + chunk.chunkId());
            }
        }
        if (version.chunkCount() != actual.size()) {
            throw importFailure(
                    "version chunk counter differs: " + expected.documentId());
        }
    }

    private static GoldenDatasetException importFailure(String message) {
        return new GoldenDatasetException("GOLDEN_DATASET_IMPORT_FAILED", message);
    }
}
