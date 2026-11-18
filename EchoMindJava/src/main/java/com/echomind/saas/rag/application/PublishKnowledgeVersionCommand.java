package com.echomind.saas.rag.application;

public record PublishKnowledgeVersionCommand(
        int versionNumber
) {
    public PublishKnowledgeVersionCommand {
        if (versionNumber < 1) {
            throw new IllegalArgumentException("versionNumber must be positive");
        }
    }
}
