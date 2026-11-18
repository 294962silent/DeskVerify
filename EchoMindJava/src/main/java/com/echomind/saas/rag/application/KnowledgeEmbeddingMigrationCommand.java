package com.echomind.saas.rag.application;

public record KnowledgeEmbeddingMigrationCommand(
        boolean dryRun,
        int limit,
        String afterDocumentId
) {
    public KnowledgeEmbeddingMigrationCommand {
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("limit must be between 1 and 500");
        }
        if (afterDocumentId != null) {
            afterDocumentId = afterDocumentId.isBlank() ? null : afterDocumentId;
            if (afterDocumentId != null && afterDocumentId.length() > 128) {
                throw new IllegalArgumentException(
                        "afterDocumentId must be at most 128 characters");
            }
        }
    }
}
