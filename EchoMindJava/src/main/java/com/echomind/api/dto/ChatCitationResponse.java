package com.echomind.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "聊天回答的结构化引用")
public record ChatCitationResponse(
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
