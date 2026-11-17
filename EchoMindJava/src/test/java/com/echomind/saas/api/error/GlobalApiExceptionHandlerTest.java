package com.echomind.saas.api.error;

import com.echomind.saas.identity.application.TenantAccessDeniedException;
import com.echomind.saas.platform.idempotency.IdempotencyConflictException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalApiExceptionHandlerTest {

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new FailingController())
            .setControllerAdvice(new GlobalApiExceptionHandler())
            .build();

    @Test
    void mapsTenantDenialToForbidden() throws Exception {
        mvc.perform(get("/tenant-denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_ACCESS_DENIED"))
                .andExpect(jsonPath("$.message").value("denied"))
                .andExpect(jsonPath("$.requestId").value("unavailable"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void mapsIdempotencyConflictToConflictAndPreservesRequestId() throws Exception {
        mvc.perform(get("/idempotency-conflict").header("X-Request-Id", "request-42"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
                .andExpect(jsonPath("$.message").value("conflicting request"))
                .andExpect(jsonPath("$.requestId").value("request-42"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void mapsValidationFailureToBadRequest() throws Exception {
        mvc.perform(post("/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("request validation failed"))
                .andExpect(jsonPath("$.requestId").value("unavailable"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @RestController
    static class FailingController {
        @GetMapping("/tenant-denied")
        String tenantDenied() {
            throw new TenantAccessDeniedException("denied");
        }

        @GetMapping("/idempotency-conflict")
        String idempotencyConflict() {
            throw new IdempotencyConflictException("conflicting request");
        }

        @PostMapping("/validate")
        String validate(@Valid @RequestBody ValidationRequest request) {
            return request.value();
        }
    }

    record ValidationRequest(@NotBlank String value) {
    }
}
