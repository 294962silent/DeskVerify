package com.echomind.saas.api.error;

import com.echomind.saas.identity.application.TenantAccessDeniedException;
import com.echomind.saas.platform.idempotency.IdempotencyConflictException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public final class GlobalApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalApiExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiErrorResponse> resourceNotFound(
            ResourceNotFoundException exception,
            HttpServletRequest request) {
        logDiagnostic(request, exception);
        return response(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND,
                "resource not found", request, false);
    }

    @ExceptionHandler(TenantAccessDeniedException.class)
    ResponseEntity<ApiErrorResponse> tenantDenied(
            TenantAccessDeniedException exception,
            HttpServletRequest request) {
        logDiagnostic(request, exception);
        return response(HttpStatus.FORBIDDEN, ErrorCode.TENANT_ACCESS_DENIED,
                "tenant access denied", request, false);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ApiErrorResponse> idempotencyConflict(
            IdempotencyConflictException exception,
            HttpServletRequest request) {
        logDiagnostic(request, exception);
        return response(HttpStatus.CONFLICT, ErrorCode.IDEMPOTENCY_CONFLICT,
                "idempotency conflict", request, false);
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HandlerMethodValidationException.class,
            ConstraintViolationException.class
    })
    ResponseEntity<ApiErrorResponse> validation(
            Exception exception,
            HttpServletRequest request) {
        logDiagnostic(request, exception);
        return response(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                "request validation failed", request, false);
    }

    private static ResponseEntity<ApiErrorResponse> response(
            HttpStatus status,
            ErrorCode code,
            String message,
            HttpServletRequest request,
            boolean retryable) {
        String requestId = requestId(request);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiErrorResponse(code.name(), message, requestId, retryable));
    }

    private static String requestId(HttpServletRequest request) {
        return RequestIdSanitizer.sanitize(request.getHeader("X-Request-Id"));
    }

    private static void logDiagnostic(HttpServletRequest request, Exception exception) {
        LOGGER.warn("api_error requestId={} exceptionClass={}",
                requestId(request), exception.getClass().getName());
    }
}
