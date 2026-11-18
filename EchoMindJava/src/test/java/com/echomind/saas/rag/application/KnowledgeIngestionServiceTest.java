package com.echomind.saas.rag.application;

import com.echomind.saas.identity.application.TenantAccessDeniedException;
import com.echomind.saas.identity.domain.ActorRole;
import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.identity.domain.TenantId;
import com.echomind.saas.identity.domain.UserId;
import com.echomind.saas.rag.domain.KnowledgeChunk;
import com.echomind.saas.rag.domain.KnowledgeDocument;
import com.echomind.saas.rag.domain.KnowledgeDocumentVersion;
import com.echomind.saas.rag.domain.KnowledgeVersionStatus;
import com.echomind.saas.rag.domain.KnowledgeVisibility;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeIngestionServiceTest {

    private final KnowledgeDocumentRepository repository =
            mock(KnowledgeDocumentRepository.class);
    private final LocalHashEmbeddingProvider embeddings = new LocalHashEmbeddingProvider();
    private final KnowledgeIngestionService service = new KnowledgeIngestionService(
            repository, new MarkdownAwareChunker(), embeddings);

    @Test
    void adminCreatesDocumentAndIndexesChunksAsDraft() {
        when(repository.insertDocument(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
        when(repository.findVersionByChecksum(
                        any(),
                        anyString(),
                        anyString(),
                        eq(embeddings.modelName())))
                .thenReturn(Optional.empty());
        when(repository.nextVersion(any(), anyString())).thenReturn(1);
        when(repository.insertVersion(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
        when(repository.findVersion(any(), anyString(), eq(1))).thenAnswer(invocation -> {
            String documentId = invocation.getArgument(1);
            return Optional.of(new KnowledgeDocumentVersion(
                    documentId,
                    1,
                    KnowledgeVersionStatus.DRAFT,
                    "a".repeat(64),
                    "normalized",
                    2,
                    embeddings.modelName(),
                    "admin-1",
                    null));
        });

        KnowledgeDocumentView view = service.create(
                new CreateKnowledgeDocumentCommand(
                        "企业版续费政策",
                        """
                                # 续费

                                续费后五分钟内更新席位。

                                # 故障

                                如果权益未生效，请刷新会话。
                                """,
                        KnowledgeVisibility.TENANT,
                        null),
                admin());
        assertThat(view.document().documentId()).startsWith("doc-");
        assertThat(view.version().status()).isEqualTo(KnowledgeVersionStatus.DRAFT);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgeChunk>> chunks = ArgumentCaptor.forClass(List.class);
        verify(repository).markIndexingAndInsertChunks(
                eq(new TenantId("tenant-a")),
                eq(view.document().documentId()),
                eq(1),
                eq(embeddings.modelName()),
                chunks.capture());
        assertThat(chunks.getValue()).isNotEmpty();
        assertThat(chunks.getValue()).allSatisfy(chunk -> {
            assertThat(chunk.documentId()).isEqualTo(view.document().documentId());
            assertThat(chunk.embedding()).hasSize(384);
            assertThat(chunk.checksum()).hasSize(64);
        });
    }

    @Test
    void duplicateNormalizedContentReturnsTheExistingVersion() {
        KnowledgeDocument document = new KnowledgeDocument(
                "doc-1", "Policy", "ACTIVE", 1, "admin-1");
        KnowledgeDocumentVersion existing = new KnowledgeDocumentVersion(
                "doc-1",
                1,
                KnowledgeVersionStatus.DRAFT,
                "a".repeat(64),
                "content",
                1,
                embeddings.modelName(),
                "admin-1",
                null);
        when(repository.findDocument(any(), eq("doc-1"))).thenReturn(Optional.of(document));
        when(repository.findVersionByChecksum(
                        any(),
                        eq("doc-1"),
                        anyString(),
                        eq(embeddings.modelName())))
                .thenReturn(Optional.of(existing));

        KnowledgeDocumentVersion version = service.addVersion(
                "doc-1",
                new AddKnowledgeVersionCommand(
                        "Same content", KnowledgeVisibility.TENANT, null),
                admin());

        assertThat(version).isEqualTo(existing);
        verify(repository).findVersionByChecksum(
                eq(new TenantId("tenant-a")),
                eq("doc-1"),
                anyString(),
                eq(embeddings.modelName()));
        verify(repository, never()).nextVersion(any(), anyString());
        verify(repository, never()).insertVersion(any(), any());
    }

    @Test
    void nonAdminCannotIngestOrPublish() {
        ActorScope customer = new ActorScope(
                new UserId("customer-1"),
                new TenantId("tenant-a"),
                Set.of(ActorRole.CUSTOMER),
                Set.of("customer-a"),
                "rag-request");

        assertThatThrownBy(() -> service.create(
                new CreateKnowledgeDocumentCommand(
                        "Policy", "Content", KnowledgeVisibility.TENANT, null),
                customer))
                .isInstanceOf(TenantAccessDeniedException.class);
    }

    @Test
    void publishVerifiesAndActivatesTheSelectedVersion() {
        KnowledgeDocumentVersion draft = new KnowledgeDocumentVersion(
                "doc-1",
                2,
                KnowledgeVersionStatus.DRAFT,
                "b".repeat(64),
                "content",
                3,
                embeddings.modelName(),
                "admin-1",
                null);
        KnowledgeDocumentVersion published = new KnowledgeDocumentVersion(
                "doc-1",
                2,
                KnowledgeVersionStatus.PUBLISHED,
                "b".repeat(64),
                "content",
                3,
                embeddings.modelName(),
                "admin-1",
                Instant.parse("2026-11-18T10:00:00Z"));
        when(repository.findDocument(any(), eq("doc-1"))).thenReturn(Optional.of(
                new KnowledgeDocument("doc-1", "Policy", "ACTIVE", 1, "admin-1")));
        when(repository.findVersion(any(), eq("doc-1"), eq(2)))
                .thenReturn(Optional.of(draft), Optional.of(published));

        KnowledgeDocumentVersion result = service.publish(
                "doc-1", new PublishKnowledgeVersionCommand(2), admin());

        assertThat(result.status()).isEqualTo(KnowledgeVersionStatus.PUBLISHED);
        verify(repository).publish(
                new TenantId("tenant-a"),
                "doc-1",
                2,
                3,
                embeddings.modelName());
    }

    private static ActorScope admin() {
        return new ActorScope(
                new UserId("admin-1"),
                new TenantId("tenant-a"),
                Set.of(ActorRole.TENANT_ADMIN),
                Set.of(),
                "rag-request");
    }
}
