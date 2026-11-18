package com.echomind.saas.rag.application;

import com.echomind.saas.rag.domain.KnowledgeVisibility;

public record CreateKnowledgeDocumentCommand(
        String title,
        String content,
        KnowledgeVisibility visibility,
        String customerId
) {
    public CreateKnowledgeDocumentCommand {
        title = KnowledgeValidation.requireText(title, "title", 255);
        content = KnowledgeValidation.requireText(content, "content", 2_000_000);
        visibility = visibility == null ? KnowledgeVisibility.TENANT : visibility;
        customerId = KnowledgeValidation.customerId(visibility, customerId);
    }
}
