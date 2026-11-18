package com.echomind.saas.rag.application;

import com.echomind.saas.identity.application.TenantAccessDeniedException;
import com.echomind.saas.identity.domain.ActorRole;
import com.echomind.saas.identity.domain.ActorScope;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeEmbeddingMigrationService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(KnowledgeEmbeddingMigrationService.class);

    private final KnowledgeDocumentRepository repository;
    private final KnowledgeEmbeddingMigrationWorker worker;
    private final EmbeddingProvider embeddings;

    public KnowledgeEmbeddingMigrationService(
            KnowledgeDocumentRepository repository,
            KnowledgeEmbeddingMigrationWorker worker,
            EmbeddingProvider embeddings) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.embeddings = Objects.requireNonNull(embeddings, "embeddings");
    }

    public KnowledgeEmbeddingMigrationResult migrate(
            KnowledgeEmbeddingMigrationCommand command,
            ActorScope actor) {
        requireAdmin(actor);
        Objects.requireNonNull(command, "command");
        Instant started = Instant.now();
        String activeModel = embeddings.modelName();
        if (!command.dryRun() && !embeddings.available()) {
            throw new EmbeddingUnavailableException(
                    "embedding model is unavailable for migration");
        }

        List<KnowledgeEmbeddingMigrationCandidate> candidates =
                repository.staleEmbeddingCandidates(
                        actor.tenantId(),
                        activeModel,
                        command.afterDocumentId(),
                        command.limit());
        List<KnowledgeEmbeddingMigrationItem> items = new ArrayList<>();
        for (KnowledgeEmbeddingMigrationCandidate candidate : candidates) {
            if (command.dryRun()) {
                items.add(planned(candidate));
                continue;
            }
            try {
                items.add(worker.migrate(candidate, actor));
            } catch (RuntimeException exception) {
                LOGGER.warn(
                        "embedding_migration_failed documentId={} exceptionClass={}",
                        candidate.documentId(),
                        exception.getClass().getName());
                items.add(failed(candidate, safeMessage(exception)));
            }
        }

        int planned = count(items, KnowledgeEmbeddingMigrationStatus.PLANNED);
        int migrated = count(items, KnowledgeEmbeddingMigrationStatus.MIGRATED);
        int skipped = count(items, KnowledgeEmbeddingMigrationStatus.SKIPPED);
        int failed = count(items, KnowledgeEmbeddingMigrationStatus.FAILED);
        long remaining = repository.countStalePublishedVersions(
                actor.tenantId(), activeModel);
        String nextAfterDocumentId = candidates.size() == command.limit()
                && remaining > 0
                ? candidates.getLast().documentId()
                : null;
        return new KnowledgeEmbeddingMigrationResult(
                command.dryRun(),
                activeModel,
                candidates.size(),
                planned,
                migrated,
                skipped,
                failed,
                remaining,
                nextAfterDocumentId,
                Duration.between(started, Instant.now()).toMillis(),
                items);
    }

    private static KnowledgeEmbeddingMigrationItem planned(
            KnowledgeEmbeddingMigrationCandidate candidate) {
        return new KnowledgeEmbeddingMigrationItem(
                candidate.documentId(),
                candidate.versionNumber(),
                candidate.embeddingModel(),
                null,
                KnowledgeEmbeddingMigrationStatus.PLANNED,
                "dry run");
    }

    private static KnowledgeEmbeddingMigrationItem failed(
            KnowledgeEmbeddingMigrationCandidate candidate, String message) {
        return new KnowledgeEmbeddingMigrationItem(
                candidate.documentId(),
                candidate.versionNumber(),
                candidate.embeddingModel(),
                null,
                KnowledgeEmbeddingMigrationStatus.FAILED,
                message);
    }

    private static int count(
            List<KnowledgeEmbeddingMigrationItem> items,
            KnowledgeEmbeddingMigrationStatus status) {
        return (int) items.stream().filter(item -> item.status() == status).count();
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private static void requireAdmin(ActorScope actor) {
        Objects.requireNonNull(actor, "actor");
        if (!actor.hasRole(ActorRole.TENANT_ADMIN)) {
            throw new TenantAccessDeniedException("tenant admin role is required");
        }
    }
}
