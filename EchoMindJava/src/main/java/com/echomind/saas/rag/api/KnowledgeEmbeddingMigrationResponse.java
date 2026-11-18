package com.echomind.saas.rag.api;

import com.echomind.saas.rag.application.KnowledgeEmbeddingMigrationItem;
import com.echomind.saas.rag.application.KnowledgeEmbeddingMigrationResult;
import java.util.List;

public record KnowledgeEmbeddingMigrationResponse(
        boolean dryRun,
        String targetEmbeddingModel,
        int scanned,
        int planned,
        int migrated,
        int skipped,
        int failed,
        long remaining,
        String nextAfterDocumentId,
        long latencyMs,
        List<Item> items
) {
    public static KnowledgeEmbeddingMigrationResponse from(
            KnowledgeEmbeddingMigrationResult result) {
        return new KnowledgeEmbeddingMigrationResponse(
                result.dryRun(),
                result.targetEmbeddingModel(),
                result.scanned(),
                result.planned(),
                result.migrated(),
                result.skipped(),
                result.failed(),
                result.remaining(),
                result.nextAfterDocumentId(),
                result.latencyMs(),
                result.items().stream().map(Item::from).toList());
    }

    public record Item(
            String documentId,
            int sourceVersionNumber,
            String sourceEmbeddingModel,
            Integer targetVersionNumber,
            String status,
            String message
    ) {
        static Item from(KnowledgeEmbeddingMigrationItem item) {
            return new Item(
                    item.documentId(),
                    item.sourceVersionNumber(),
                    item.sourceEmbeddingModel(),
                    item.targetVersionNumber(),
                    item.status().name(),
                    item.message());
        }
    }
}
