package com.echomind.saas.rag.application;

import com.echomind.saas.rag.domain.KnowledgeVisibility;

public record AddKnowledgeVersionCommand(
        String content,
        KnowledgeVisibility visibility,
        String customerId
) {
    public AddKnowledgeVersionCommand {
        content = KnowledgeValidation.requireText(content, "content", 2_000_000);
        visibility = visibility == null ? KnowledgeVisibility.TENANT : visibility;
        customerId = KnowledgeValidation.customerId(visibility, customerId);
    }
}
