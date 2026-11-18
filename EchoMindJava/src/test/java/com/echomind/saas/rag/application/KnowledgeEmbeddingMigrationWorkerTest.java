package com.echomind.saas.rag.application;

import com.echomind.saas.identity.domain.ActorRole;
import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.identity.domain.UserId;
import com.echomind.saas.rag.domain.KnowledgeDocument;
import com.echomind.saas.rag.domain.KnowledgeDocumentVersion;
import com.echomind.saas.rag.domain.KnowledgeVersionStatus;
import com.echomind.saas.rag.domain.KnowledgeVisibility;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeEmbeddingMigrationWorkerTest {

    private static final String ACTIVE_MODEL = "bge-small-zh-v1.5-onnx-fp32";
    private static final TenantId TENANT = new TenantId("tenant-a");
    private static final ActorScope ADMIN = new ActorScope(
            new UserId("admin-1"),
            TENANT,
            Set.of(ActorRole.TENANT_ADMIN),
            Set.of(),
            "migration-request");

    private final KnowledgeDocumentRepository repository =
            mock(KnowledgeDocumentRepository.class);
    private final KnowledgeIngestionService ingestion =
            mock(KnowledgeIngestionService.class);
    private final EmbeddingProvider embeddings = mock(EmbeddingProvider.class);
    private final KnowledgeEmbeddingMigrationWorker worker =
            new KnowledgeEmbeddingMigrationWorker(repository, ingestion, embeddings);

    @Test
    void reindexesAndPublishesUsingSourceVisibility() {
        KnowledgeDocumentVersion source = sourceVersion();
        KnowledgeDocumentVersion target = version(
                2, KnowledgeVersionStatus.DRAFT, ACTIVE_MODEL, 2);
        KnowledgeDocumentVersion published = version(
                2, KnowledgeVersionStatus.PUBLISHED, ACTIVE_MODEL, 2);
        when(repository.findDocumentForUpdate(TENANT, "doc-1"))
                .thenReturn(Optional.of(document(1)));
        when(repository.findVersion(TENANT, "doc-1", 1))
                .thenReturn(Optional.of(source));
        when(embeddings.modelName()).thenReturn(ACTIVE_MODEL);
        when(repository.versionChunkAclSummaries(TENANT, "doc-1", 1))
                .thenReturn(List.of(new KnowledgeChunkAclSummary(
                        KnowledgeVisibility.CUSTOMER, "customer-a", 2)));
        when(ingestion.addVersion(eq("doc-1"), any(AddKnowledgeVersionCommand.class), eq(ADMIN)))
                .thenReturn(target);
        when(repository.versionChunkAclSummaries(TENANT, "doc-1", 2))
                .thenReturn(List.of(new KnowledgeChunkAclSummary(
                        KnowledgeVisibility.CUSTOMER, "customer-a", 2)));
        when(ingestion.publish(
                        "doc-1",
                        new PublishKnowledgeVersionCommand(2),
                        ADMIN))
                .thenReturn(published);

        KnowledgeEmbeddingMigrationItem result = worker.migrate(candidate(), ADMIN);

        assertThat(result.status()).isEqualTo(KnowledgeEmbeddingMigrationStatus.MIGRATED);
        assertThat(result.targetVersionNumber()).isEqualTo(2);
        ArgumentCaptor<AddKnowledgeVersionCommand> command =
                ArgumentCaptor.forClass(AddKnowledgeVersionCommand.class);
        verify(ingestion).addVersion(eq("doc-1"), command.capture(), eq(ADMIN));
        assertThat(command.getValue().content()).isEqualTo(source.normalizedText());
        assertThat(command.getValue().visibility()).isEqualTo(KnowledgeVisibility.CUSTOMER);
        assertThat(command.getValue().customerId()).isEqualTo("customer-a");
        verify(ingestion).publish(
                "doc-1", new PublishKnowledgeVersionCommand(2), ADMIN);
    }

    @Test
    void skipsWhenDocumentCurrentVersionChanged() {
        KnowledgeDocumentVersion source = sourceVersion();
        when(repository.findDocumentForUpdate(TENANT, "doc-1"))
                .thenReturn(Optional.of(document(2)));

        KnowledgeEmbeddingMigrationItem result = worker.migrate(candidate(), ADMIN);

        assertThat(result.status()).isEqualTo(KnowledgeEmbeddingMigrationStatus.SKIPPED);
        assertThat(result.message()).contains("current version changed");
        verify(ingestion, never()).addVersion(any(), any(), any());
    }

    @Test
    void rejectsMixedSourceAclBeforeCreatingTarget() {
        KnowledgeDocumentVersion source = sourceVersion();
        when(repository.findDocumentForUpdate(TENANT, "doc-1"))
                .thenReturn(Optional.of(document(1)));
        when(repository.findVersion(TENANT, "doc-1", 1))
                .thenReturn(Optional.of(source));
        when(embeddings.modelName()).thenReturn(ACTIVE_MODEL);
        when(repository.versionChunkAclSummaries(TENANT, "doc-1", 1))
                .thenReturn(List.of(
                        new KnowledgeChunkAclSummary(
                                KnowledgeVisibility.TENANT, null, 1),
                        new KnowledgeChunkAclSummary(
                                KnowledgeVisibility.CUSTOMER, "customer-a", 1)));

        assertThatThrownBy(() -> worker.migrate(candidate(), ADMIN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one ACL scope");
        verify(ingestion, never()).addVersion(any(), any(), any());
    }

    @Test
    void rejectsTargetAclDifferentFromSourceBeforePublishing() {
        KnowledgeDocumentVersion source = sourceVersion();
        KnowledgeDocumentVersion target = version(
                2, KnowledgeVersionStatus.DRAFT, ACTIVE_MODEL, 2);
        when(repository.findDocumentForUpdate(TENANT, "doc-1"))
                .thenReturn(Optional.of(document(1)));
        when(repository.findVersion(TENANT, "doc-1", 1))
                .thenReturn(Optional.of(source));
        when(embeddings.modelName()).thenReturn(ACTIVE_MODEL);
        when(repository.versionChunkAclSummaries(TENANT, "doc-1", 1))
                .thenReturn(List.of(new KnowledgeChunkAclSummary(
                        KnowledgeVisibility.TENANT, null, 2)));
        when(ingestion.addVersion(eq("doc-1"), any(AddKnowledgeVersionCommand.class), eq(ADMIN)))
                .thenReturn(target);
        when(repository.versionChunkAclSummaries(TENANT, "doc-1", 2))
                .thenReturn(List.of(new KnowledgeChunkAclSummary(
                        KnowledgeVisibility.CUSTOMER, "customer-a", 2)));

        assertThatThrownBy(() -> worker.migrate(candidate(), ADMIN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACL differs");
        verify(ingestion, never()).publish(any(), any(), any());
    }

    private static KnowledgeDocumentVersion sourceVersion() {
        return legacyVersion(1);
    }

    private static KnowledgeEmbeddingMigrationCandidate candidate() {
        return new KnowledgeEmbeddingMigrationCandidate(
                "doc-1", 1, "local-hash-char-ngram-v1");
    }

    private static KnowledgeDocumentVersion legacyVersion(int versionNumber) {
        return new KnowledgeDocumentVersion(
                "doc-1",
                versionNumber,
                KnowledgeVersionStatus.PUBLISHED,
                "a".repeat(64),
                "# 退款\n\n退款五到七个工作日到账。",
                2,
                "local-hash-char-ngram-v1",
                "admin-1",
                null);
    }

    private static KnowledgeDocumentVersion version(
            int versionNumber,
            KnowledgeVersionStatus status,
            String model,
            int chunkCount) {
        return new KnowledgeDocumentVersion(
                "doc-1",
                versionNumber,
                status,
                "a".repeat(64),
                "# 退款\n\n退款五到七个工作日到账。",
                chunkCount,
                model,
                "admin-1",
                null);
    }

    private static KnowledgeDocument document(int currentVersion) {
        return new KnowledgeDocument(
                "doc-1", "退款政策", "ACTIVE", currentVersion, "admin-1");
    }
}
