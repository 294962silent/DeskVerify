package com.echomind.saas.rag.domain;

import java.time.Instant;
import java.util.Objects;

public record KnowledgeDocumentVersion(
        String documentId,
        int versionNumber,
        KnowledgeVersionStatus status,
        String sourceChecksum,
        String normalizedText,
        int chunkCount,
        String embeddingModel,
        String createdByUserId,
        Instant publishedAt
) {
    public KnowledgeDocumentVersion {
        documentId = requireText(documentId, "documentId", 128);
        if (versionNumber < 1) {
            throw new IllegalArgumentException("versionNumber must be positive");
        }
        status = Objects.requireNonNull(status, "status");
        sourceChecksum = requireText(sourceChecksum, "sourceChecksum", 64);
        normalizedText = Objects.requireNonNull(normalizedText, "normalizedText");
        if (chunkCount < 0) {
            throw new IllegalArgumentException("chunkCount must not be negative");
        }
        embeddingModel = requireText(embeddingModel, "embeddingModel", 80);
        createdByUserId = requireText(createdByUserId, "createdByUserId", 128);
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must be nonblank and at most " + maxLength + " characters");
        }
        return value;
    }
}
