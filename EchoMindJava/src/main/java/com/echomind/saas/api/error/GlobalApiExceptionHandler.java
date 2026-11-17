package com.echomind.saas.api.error;

import com.echomind.saas.identity.application.TenantAccessDeniedException;
import com.echomind.saas.platform.idempotency.IdempotencyConflictException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class GlobalApiExceptionHandler {

    @ExceptionHandler(TenantAccessDeniedException.class)
    ResponseEntity<ApiErrorResponse> tenantDenied(
            TenantAccessDeniedException exception,
            HttpServletRequest request) {
        return response(HttpStatus.FORBIDDEN, ErrorCode.TENANT_ACCESS_DENIED,
                exception.getMessage(), request, false);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ApiErrorResponse> idempotencyConflict(
            IdempotencyConflictException exception,
            HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, ErrorCode.IDEMPOTENCY_CONFLICT,
                exception.getMessage(), request, false);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> validation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                "request validation failed", request, false);
    }

    private static ResponseEntity<ApiErrorResponse> response(
            HttpStatus status,
            ErrorCode code,
            String message,
            HttpServletRequest request,
            boolean retryable) {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            requestId = "unavailable";
        }
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(code.name(), message, requestId, retryable));
    }
}
