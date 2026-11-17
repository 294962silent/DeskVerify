package com.echomind.saas.api.error;

public record ApiErrorResponse(
        String code,
        String message,
        String requestId,
        boolean retryable
) {
}
