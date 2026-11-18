package com.echomind.saas.rag.application;

import com.echomind.saas.identity.application.TenantAccessDeniedException;
import com.echomind.saas.identity.domain.ActorRole;
import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.identity.domain.UserId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeEmbeddingMigrationServiceTest {

    private static final String ACTIVE_MODEL = "bge-small-zh-v1.5-onnx-fp32";
    private static final TenantId TENANT = new TenantId("tenant-a");

    private final KnowledgeDocumentRepository repository =
            mock(KnowledgeDocumentRepository.class);
    private final KnowledgeEmbeddingMigrationWorker worker =
            mock(KnowledgeEmbeddingMigrationWorker.class);
    private final EmbeddingProvider embeddings = mock(EmbeddingProvider.class);
    private final KnowledgeEmbeddingMigrationService service =
            new KnowledgeEmbeddingMigrationService(repository, worker, embeddings);

    @Test
    void dryRunPlansOneCursorPageWithoutLoadingEmbeddings() {
        KnowledgeEmbeddingMigrationCandidate first = legacy("doc-a", 1);
        KnowledgeEmbeddingMigrationCandidate second = legacy("doc-b", 1);
        when(embeddings.modelName()).thenReturn(ACTIVE_MODEL);
        when(repository.staleEmbeddingCandidates(
                        TENANT, ACTIVE_MODEL, "doc-0", 2))
                .thenReturn(List.of(first, second));
        when(repository.countStalePublishedVersions(TENANT, ACTIVE_MODEL))
                .thenReturn(2L);

        KnowledgeEmbeddingMigrationResult result = service.migrate(
                new KnowledgeEmbeddingMigrationCommand(true, 2, "doc-0"),
                admin());

        assertThat(result.dryRun()).isTrue();
        assertThat(result.scanned()).isEqualTo(2);
        assertThat(result.planned()).isEqualTo(2);
        assertThat(result.nextAfterDocumentId()).isEqualTo("doc-b");
        assertThat(result.remaining()).isEqualTo(2);
        assertThat(result.items())
                .extracting(KnowledgeEmbeddingMigrationItem::status)
                .containsOnly(KnowledgeEmbeddingMigrationStatus.PLANNED);
        verify(embeddings, never()).available();
        verify(worker, never()).migrate(any(), any());
    }

    @Test
    void failedDocumentDoesNotBlockLaterDocuments() {
        KnowledgeEmbeddingMigrationCandidate first = legacy("doc-a", 1);
        KnowledgeEmbeddingMigrationCandidate second = legacy("doc-b", 1);
        when(embeddings.modelName()).thenReturn(ACTIVE_MODEL);
        when(embeddings.available()).thenReturn(true);
        when(repository.staleEmbeddingCandidates(any(), eq(ACTIVE_MODEL), eq(null), eq(2)))
                .thenReturn(List.of(first, second));
        when(worker.migrate(first, admin()))
                .thenThrow(new IllegalStateException("target ACL mismatch"));
        when(worker.migrate(second, admin())).thenReturn(item(
                second,
                2,
                KnowledgeEmbeddingMigrationStatus.MIGRATED,
                "reindexed and published"));
        when(repository.countStalePublishedVersions(TENANT, ACTIVE_MODEL))
                .thenReturn(1L);

        KnowledgeEmbeddingMigrationResult result = service.migrate(
                new KnowledgeEmbeddingMigrationCommand(false, 2, null),
                admin());

        assertThat(result.migrated()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.remaining()).isEqualTo(1);
        assertThat(result.items()).extracting(KnowledgeEmbeddingMigrationItem::status)
                .containsExactly(
                        KnowledgeEmbeddingMigrationStatus.FAILED,
                        KnowledgeEmbeddingMigrationStatus.MIGRATED);
        assertThat(result.items().getFirst().message())
                .isEqualTo("target ACL mismatch");
        verify(worker).migrate(first, admin());
        verify(worker).migrate(second, admin());
    }

    @Test
    void rerunAfterSuccessfulMigrationFindsNoCandidates() {
        when(embeddings.modelName()).thenReturn(ACTIVE_MODEL);
        when(embeddings.available()).thenReturn(true);
        when(repository.staleEmbeddingCandidates(
                        TENANT, ACTIVE_MODEL, null, 100))
                .thenReturn(List.of());
        when(repository.countStalePublishedVersions(TENANT, ACTIVE_MODEL))
                .thenReturn(0L);

        KnowledgeEmbeddingMigrationResult result = service.migrate(
                new KnowledgeEmbeddingMigrationCommand(false, 100, null),
                admin());

        assertThat(result.scanned()).isZero();
        assertThat(result.migrated()).isZero();
        assertThat(result.remaining()).isZero();
        assertThat(result.nextAfterDocumentId()).isNull();
        verify(worker, never()).migrate(any(), any());
    }

    @Test
    void customerCannotRunMigration() {
        ActorScope customer = new ActorScope(
                new UserId("customer-1"),
                TENANT,
                Set.of(ActorRole.CUSTOMER),
                Set.of("customer-a"),
                "migration-request");

        assertThatThrownBy(() -> service.migrate(
                new KnowledgeEmbeddingMigrationCommand(false, 100, null),
                customer))
                .isInstanceOf(TenantAccessDeniedException.class);
        verify(repository, never()).staleEmbeddingCandidates(
                any(), any(), any(), anyInt());
    }

    private static KnowledgeEmbeddingMigrationItem item(
            KnowledgeEmbeddingMigrationCandidate source,
            Integer targetVersion,
            KnowledgeEmbeddingMigrationStatus status,
            String message) {
        return new KnowledgeEmbeddingMigrationItem(
                source.documentId(),
                source.versionNumber(),
                source.embeddingModel(),
                targetVersion,
                status,
                message);
    }

    private static KnowledgeEmbeddingMigrationCandidate legacy(
            String documentId, int version) {
        return new KnowledgeEmbeddingMigrationCandidate(
                documentId,
                version,
                "local-hash-char-ngram-v1");
    }

    private static ActorScope admin() {
        return new ActorScope(
                new UserId("admin-1"),
                TENANT,
                Set.of(ActorRole.TENANT_ADMIN),
                Set.of(),
                "migration-request");
    }
}
