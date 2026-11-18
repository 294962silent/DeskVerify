package com.echomind.saas.rag.application;

import com.echomind.saas.rag.domain.KnowledgeVisibility;
import java.util.Objects;

public record KnowledgeChunkAclSummary(
        KnowledgeVisibility visibility,
        String customerId,
        int chunkCount
) {
    public KnowledgeChunkAclSummary {
        visibility = Objects.requireNonNull(visibility, "visibility");
        if (chunkCount < 0) {
            throw new IllegalArgumentException("chunkCount must not be negative");
        }
        if (visibility == KnowledgeVisibility.CUSTOMER
                && (customerId == null || customerId.isBlank())) {
            throw new IllegalArgumentException(
                    "customer-visible chunks require a customer id");
        }
        if (visibility == KnowledgeVisibility.TENANT && customerId != null) {
            throw new IllegalArgumentException(
                    "tenant-visible chunks must not carry a customer id");
        }
    }
}
