package com.echomind.saas.rag.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KnowledgeSearchRequest(
        @NotBlank @Size(max = 2_000) String query,
        @Min(1) @Max(20) int topK
) {
    public KnowledgeSearchRequest {
        if (topK == 0) {
            topK = 5;
        }
    }
}
