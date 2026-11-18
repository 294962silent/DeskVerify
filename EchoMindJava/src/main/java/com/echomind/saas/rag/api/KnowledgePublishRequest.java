package com.echomind.saas.rag.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record KnowledgePublishRequest(
        @NotNull @Min(1) Integer versionNumber
) {
}
