package com.echomind.saas.rag.application;

import java.util.Objects;

public record KnowledgeEmbeddingMigrationItem(
        String documentId,
        int sourceVersionNumber,
        String sourceEmbeddingModel,
        Integer targetVersionNumber,
        KnowledgeEmbeddingMigrationStatus status,
        String message
) {
    public KnowledgeEmbeddingMigrationItem {
        documentId = KnowledgeValidation.requireText(documentId, "documentId", 128);
        if (sourceVersionNumber < 1) {
            throw new IllegalArgumentException("sourceVersionNumber must be positive");
        }
        sourceEmbeddingModel = KnowledgeValidation.requireText(
                sourceEmbeddingModel, "sourceEmbeddingModel", 80);
        if (targetVersionNumber != null && targetVersionNumber < 1) {
            throw new IllegalArgumentException("targetVersionNumber must be positive");
        }
        status = Objects.requireNonNull(status, "status");
        message = message == null ? "" : message;
    }
}
