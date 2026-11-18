package com.echomind.saas.rag.application;

public final class EmbeddingUnavailableException extends RuntimeException {
    public EmbeddingUnavailableException(String message) {
        super(message);
    }

    public EmbeddingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
