package com.echomind.saas.rag.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record KnowledgeEmbeddingMigrationRequest(
        boolean dryRun,
        @Min(1) @Max(500) Integer limit,
        @Size(max = 128) String afterDocumentId
) {
    public int pageSize() {
        return limit == null ? 100 : limit;
    }
}
