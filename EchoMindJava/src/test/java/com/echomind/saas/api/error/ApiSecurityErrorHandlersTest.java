package com.echomind.saas.api.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

import static org.assertj.core.api.Assertions.assertThat;

class ApiSecurityErrorHandlersTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void authenticationEntryPointWritesStableEnvelopeWithoutExceptionDetails() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "request-auth");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ApiAuthenticationEntryPoint(objectMapper).commence(
                request,
                response,
                new BadCredentialsException("raw authentication diagnostic")
        );

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains("\"code\":\"AUTHENTICATION_REQUIRED\"")
                .contains("\"message\":\"authentication required\"")
                .contains("\"requestId\":\"request-auth\"")
                .contains("\"retryable\":false")
                .doesNotContain("raw authentication diagnostic");
    }

    @Test
    void accessDeniedHandlerWritesStableEnvelopeWithoutExceptionDetails() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "request-denied");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ApiAccessDeniedHandler(objectMapper).handle(
                request,
                response,
                new AccessDeniedException("raw authorization diagnostic")
        );

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains("\"code\":\"ACCESS_DENIED\"")
                .contains("\"message\":\"access denied\"")
                .contains("\"requestId\":\"request-denied\"")
                .contains("\"retryable\":false")
                .doesNotContain("raw authorization diagnostic");
    }
}
