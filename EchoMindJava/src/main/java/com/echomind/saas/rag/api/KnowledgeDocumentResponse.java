package com.echomind.saas.rag.api;

import com.echomind.saas.rag.application.KnowledgeDocumentView;
import com.echomind.saas.rag.domain.KnowledgeDocument;
import com.echomind.saas.rag.domain.KnowledgeDocumentVersion;
import java.time.Instant;

public record KnowledgeDocumentResponse(
        String documentId,
        String title,
        String status,
        Integer currentVersion,
        VersionResponse version
) {
    public static KnowledgeDocumentResponse from(KnowledgeDocumentView view) {
        return new KnowledgeDocumentResponse(
                view.document().documentId(),
                view.document().title(),
                view.document().status(),
                view.document().currentVersion(),
                VersionResponse.from(view.version()));
    }

    public record VersionResponse(
            int versionNumber,
            String status,
            String sourceChecksum,
            int chunkCount,
            String embeddingModel,
            Instant publishedAt
    ) {
        public static VersionResponse from(KnowledgeDocumentVersion version) {
            return new VersionResponse(
                    version.versionNumber(),
                    version.status().name(),
                    version.sourceChecksum(),
                    version.chunkCount(),
                    version.embeddingModel(),
                    version.publishedAt());
        }
    }
}
