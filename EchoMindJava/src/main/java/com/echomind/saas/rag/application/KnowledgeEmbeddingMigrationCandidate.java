package com.echomind.saas.rag.application;

public record KnowledgeEmbeddingMigrationCandidate(
        String documentId,
        int versionNumber,
        String embeddingModel
) {
    public KnowledgeEmbeddingMigrationCandidate {
        documentId = KnowledgeValidation.requireText(documentId, "documentId", 128);
        if (versionNumber < 1) {
            throw new IllegalArgumentException("versionNumber must be positive");
        }
        embeddingModel = KnowledgeValidation.requireText(
                embeddingModel, "embeddingModel", 80);
    }
}
