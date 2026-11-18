package com.echomind.saas.evaluation.golden;

import com.echomind.saas.rag.domain.KnowledgeVisibility;
import java.util.List;
import java.util.Objects;

public record GoldenDataset(
        String datasetVersion,
        int schemaVersion,
        String tenantId,
        Provenance provenance,
        List<Document> documents,
        List<RetrievalCase> retrievalCases,
        List<HandoffCase> handoffCases
) {
    public GoldenDataset {
        datasetVersion = requireText(datasetVersion, "datasetVersion");
        tenantId = requireText(tenantId, "tenantId");
        provenance = Objects.requireNonNull(provenance, "provenance");
        documents = List.copyOf(Objects.requireNonNull(documents, "documents"));
        retrievalCases = List.copyOf(Objects.requireNonNull(retrievalCases, "retrievalCases"));
        handoffCases = List.copyOf(Objects.requireNonNull(handoffCases, "handoffCases"));
    }

    public enum Split {
        DEV,
        HOLDOUT
    }

    public enum ActorScope {
        TENANT_ADMIN,
        CUSTOMER_A,
        CUSTOMER_B
    }

    public record Provenance(
            String kind,
            boolean humanReviewed,
            boolean statisticallyRepresentative,
            int externalLlmTokens
    ) {
        public Provenance {
            kind = requireText(kind, "kind");
            if (externalLlmTokens < 0) {
                throw new IllegalArgumentException("externalLlmTokens must not be negative");
            }
        }
    }

    public record Document(
            String documentId,
            String title,
            int version,
            KnowledgeVisibility visibility,
            String customerId,
            String normalizedContentSha256,
            String content,
            List<ChunkExpectation> expectedChunks
    ) {
        public Document {
            documentId = requireText(documentId, "documentId");
            title = requireText(title, "title");
            visibility = Objects.requireNonNull(visibility, "visibility");
            normalizedContentSha256 = requireText(
                    normalizedContentSha256, "normalizedContentSha256");
            content = requireText(content, "content");
            expectedChunks = List.copyOf(Objects.requireNonNull(expectedChunks, "expectedChunks"));
            if (version < 1) {
                throw new IllegalArgumentException("version must be positive");
            }
        }
    }

    public record ChunkExpectation(
            int chunkIndex,
            String chunkId,
            String checksum,
            String sectionTitle,
            String headingPath
    ) {
        public ChunkExpectation {
            chunkId = requireText(chunkId, "chunkId");
            checksum = requireText(checksum, "checksum");
            sectionTitle = requireText(sectionTitle, "sectionTitle");
            headingPath = requireText(headingPath, "headingPath");
            if (chunkIndex < 0) {
                throw new IllegalArgumentException("chunkIndex must not be negative");
            }
        }
    }

    public record RetrievalCase(
            String caseId,
            Split split,
            String query,
            ActorScope actorScope,
            String expectedDocumentId,
            int expectedVersionNumber,
            String expectedChunkId,
            String expectedHeadingPath,
            boolean expectCitation
    ) {
        public RetrievalCase {
            caseId = requireText(caseId, "caseId");
            split = Objects.requireNonNull(split, "split");
            query = requireText(query, "query");
            actorScope = Objects.requireNonNull(actorScope, "actorScope");
            expectedDocumentId = requireText(expectedDocumentId, "expectedDocumentId");
            expectedChunkId = requireText(expectedChunkId, "expectedChunkId");
            expectedHeadingPath = requireText(expectedHeadingPath, "expectedHeadingPath");
            if (expectedVersionNumber < 1) {
                throw new IllegalArgumentException("expectedVersionNumber must be positive");
            }
        }
    }

    public record HandoffCase(
            String caseId,
            Split split,
            String message,
            HandoffSignals signals,
            boolean expectedHandoff,
            String expectedReason
    ) {
        public HandoffCase {
            caseId = requireText(caseId, "caseId");
            split = Objects.requireNonNull(split, "split");
            message = requireText(message, "message");
            signals = Objects.requireNonNull(signals, "signals");
            expectedReason = requireText(expectedReason, "expectedReason");
        }
    }

    public record HandoffSignals(
            boolean explicitHumanRequest,
            boolean criticalUrgency,
            boolean securityOrPrivacy,
            boolean monetaryDispute,
            boolean irreversibleAction,
            boolean verifiedKnowledgeAvailable
    ) {
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
