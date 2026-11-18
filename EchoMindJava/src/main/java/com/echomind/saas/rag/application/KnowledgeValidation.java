package com.echomind.saas.rag.application;

import com.echomind.saas.rag.domain.KnowledgeVisibility;

final class KnowledgeValidation {

    private KnowledgeValidation() {
    }

    static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must be nonblank and at most " + maxLength + " characters");
        }
        return value;
    }

    static String customerId(KnowledgeVisibility visibility, String customerId) {
        if (visibility == KnowledgeVisibility.CUSTOMER) {
            return requireText(customerId, "customerId", 128);
        }
        if (customerId != null && !customerId.isBlank()) {
            throw new IllegalArgumentException(
                    "customerId is only valid for customer-visible knowledge");
        }
        return null;
    }
}
