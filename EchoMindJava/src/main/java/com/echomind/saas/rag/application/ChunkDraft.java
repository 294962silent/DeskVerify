package com.echomind.saas.rag.application;

public record ChunkDraft(
        int chunkIndex,
        String content,
        Integer pageNumber,
        String sectionTitle,
        String headingPath,
        int charStart,
        int charEnd,
        String checksum
) {
}
