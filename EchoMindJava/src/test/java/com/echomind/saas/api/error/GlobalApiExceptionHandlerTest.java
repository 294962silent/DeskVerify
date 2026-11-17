package com.echomind.saas.api.error;

import com.echomind.saas.identity.application.TenantAccessDeniedException;
import com.echomind.saas.platform.idempotency.IdempotencyConflictException;
import jakarta.validation.Valid;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
    void mapsTenantDenialToStableForbiddenResponse() throws Exception {
        mvc.perform(get("/tenant-denied").header("X-Request-Id", "request-tenant"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("TENANT_ACCESS_DENIED"))
                .andExpect(jsonPath("$.message").value("tenant access denied"))
                .andExpect(jsonPath("$.requestId").value("request-tenant"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(content().string(not(containsString("raw-tenant-diagnostic"))));
    }

    @Test
    void blankRequestIdFallsBackToUnavailable() throws Exception {
        mvc.perform(get("/tenant-denied").header("X-Request-Id", "   "))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.requestId").value("unavailable"));
    }

    @Test
    void mapsIdempotencyConflictToStableConflictResponse() throws Exception {
        mvc.perform(get("/idempotency-conflict").header("X-Request-Id", "request-42"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
                .andExpect(jsonPath("$.message").value("idempotency conflict"))
                .andExpect(jsonPath("$.requestId").value("request-42"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(content().string(not(containsString("raw-idempotency-diagnostic"))));
    }

    @Test
    void mapsBeanValidationFailureToBadRequest() throws Exception {
        expectValidationFailure(mvc.perform(post("/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"\"}")));
    }

    @Test
    void mapsMalformedJsonBodyToBadRequest() throws Exception {
        expectValidationFailure(mvc.perform(post("/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":")));
    }

    @Test
    void mapsMissingJsonBodyToBadRequest() throws Exception {
        expectValidationFailure(mvc.perform(post("/validate")
                .contentType(MediaType.APPLICATION_JSON)));
    }

    @Test
    void mapsMissingRequestParameterToBadRequest() throws Exception {
        expectValidationFailure(mvc.perform(get("/required-parameter")));
    }

    @Test
    void mapsRequestParameterTypeMismatchToBadRequest() throws Exception {
        expectValidationFailure(mvc.perform(get("/typed-parameter").param("value", "not-a-number")));
    }

    @Test
    void mapsControllerParameterValidationToBadRequest() throws Exception {
        expectValidationFailure(mvc.perform(get("/validated-parameter").param("value", "0")));
    }

    @Test
    void mapsConstraintViolationToBadRequest() throws Exception {
        expectValidationFailure(mvc.perform(get("/constraint-violation")));
    }

    @RestController
    static class FailingController {
        @GetMapping("/tenant-denied")
        String tenantDenied() {
            throw new TenantAccessDeniedException("raw-tenant-diagnostic");
        }

        @GetMapping("/idempotency-conflict")
        String idempotencyConflict() {
            throw new IdempotencyConflictException("raw-idempotency-diagnostic");
        }

        @PostMapping("/validate")
        String validate(@Valid @RequestBody ValidationRequest request) {
            return request.value();
        }

        @GetMapping("/required-parameter")
        String requiredParameter(@RequestParam String value) {
            return value;
        }

        @GetMapping("/typed-parameter")
        String typedParameter(@RequestParam int value) {
            return Integer.toString(value);
        }

        @GetMapping("/validated-parameter")
        String validatedParameter(@RequestParam @Min(1) int value) {
            return Integer.toString(value);
        }

        @GetMapping("/constraint-violation")
        String constraintViolation() {
            throw new ConstraintViolationException(Set.of());
        }
    }

    record ValidationRequest(@NotBlank String value) {
    }

    private static void expectValidationFailure(ResultActions result) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("request validation failed"))
                .andExpect(jsonPath("$.requestId").value("unavailable"))
                .andExpect(jsonPath("$.retryable").value(false));
    }
}
