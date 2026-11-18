package com.echomind.saas.rag.domain;

import java.util.Objects;

public record KnowledgeDocument(
        String documentId,
        String title,
        String status,
        Integer currentVersion,
        String createdByUserId
) {
    public KnowledgeDocument {
        documentId = requireText(documentId, "documentId", 128);
        title = requireText(title, "title", 255);
        status = requireText(status, "status", 32);
        createdByUserId = requireText(createdByUserId, "createdByUserId", 128);
        if (currentVersion != null && currentVersion < 1) {
            throw new IllegalArgumentException("currentVersion must be positive");
        }
    }

    private static String requireText(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must be nonblank and at most " + maxLength + " characters");
        }
        return value;
    }
}
