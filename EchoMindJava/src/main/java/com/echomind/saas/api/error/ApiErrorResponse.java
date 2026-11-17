package com.echomind.saas.api.error;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ApiErrorResponse(
        String code,
        String message,
        @JsonProperty("requestId")
        String requestId,
        boolean retryable
) {
}
