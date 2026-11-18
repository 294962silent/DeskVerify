package com.echomind.saas.rag.application;

import java.util.List;

public record KnowledgeEmbeddingMigrationResult(
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
        List<KnowledgeEmbeddingMigrationItem> items
) {
    public KnowledgeEmbeddingMigrationResult {
        items = List.copyOf(items);
    }
}
