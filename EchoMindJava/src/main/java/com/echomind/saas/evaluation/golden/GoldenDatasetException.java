package com.echomind.saas.evaluation.golden;

public final class GoldenDatasetException extends RuntimeException {

    private final String errorCode;

    public GoldenDatasetException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public GoldenDatasetException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }

    public static GoldenDatasetException integrity(String message) {
        return new GoldenDatasetException("GOLDEN_DATASET_INTEGRITY_FAILED", message);
    }
}
