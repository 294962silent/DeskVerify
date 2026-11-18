package com.echomind.saas.rag.domain;

public record KnowledgeCitation(
        String citationId,
        String chunkId,
        String documentId,
        String documentTitle,
        int versionNumber,
        Integer pageNumber,
        String sectionTitle,
        String headingPath,
        String excerpt,
        int charStart,
        int charEnd,
        double score
) {
}
