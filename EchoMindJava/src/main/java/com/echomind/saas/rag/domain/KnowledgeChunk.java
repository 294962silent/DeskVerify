package com.echomind.saas.rag.domain;

import java.util.Objects;

public record KnowledgeChunk(
        String chunkId,
        String documentId,
        int versionNumber,
        int chunkIndex,
        String documentTitle,
        String content,
        Integer pageNumber,
        String sectionTitle,
        String headingPath,
        int charStart,
        int charEnd,
        String checksum,
        KnowledgeVisibility visibility,
        String customerId,
        double[] embedding
) {
    public KnowledgeChunk {
        chunkId = requireText(chunkId, "chunkId", 128);
        documentId = requireText(documentId, "documentId", 128);
        if (versionNumber < 1 || chunkIndex < 0) {
            throw new IllegalArgumentException("version and chunk index are invalid");
        }
        documentTitle = requireText(documentTitle, "documentTitle", 255);
        content = Objects.requireNonNull(content, "content");
        visibility = Objects.requireNonNull(visibility, "visibility");
        if (visibility == KnowledgeVisibility.CUSTOMER) {
            customerId = requireText(customerId, "customerId", 128);
        } else if (customerId != null) {
            throw new IllegalArgumentException(
                    "tenant-visible chunks must not carry a customer id");
        }
        if (charStart < 0 || charEnd <= charStart) {
            throw new IllegalArgumentException("invalid chunk character range");
        }
        checksum = requireText(checksum, "checksum", 64);
        embedding = embedding == null ? new double[0] : embedding.clone();
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must be nonblank and at most " + maxLength + " characters");
        }
        return value;
    }
}
