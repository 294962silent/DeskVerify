package com.echomind.saas.rag.application;

import com.echomind.saas.rag.domain.KnowledgeDocument;
import com.echomind.saas.rag.domain.KnowledgeDocumentVersion;

public record KnowledgeDocumentView(
        KnowledgeDocument document,
        KnowledgeDocumentVersion version
) {
}
