package com.echomind.saas.rag.api;

import com.echomind.saas.rag.domain.KnowledgeVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KnowledgeVersionRequest(
        @NotBlank @Size(max = 2_000_000) String content,
        KnowledgeVisibility visibility,
        @Size(max = 128) String customerId
) {
}
