package com.echomind.saas.api.error;

import com.echomind.saas.identity.security.AuthenticationFailureResponder;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

@Component
public final class ApiAuthenticationEntryPoint implements AuthenticationFailureResponder {

    private final ObjectMapper objectMapper;

    public ApiAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), new ApiErrorResponse(
                ErrorCode.AUTHENTICATION_REQUIRED.name(),
                "authentication required",
                RequestIdSanitizer.sanitize(request.getHeader("X-Request-Id")),
                false
        ));
    }
}
