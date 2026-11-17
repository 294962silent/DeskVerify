package com.echomind.saas.api.error;

import com.echomind.saas.identity.security.AccessDeniedFailureResponder;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
public final class ApiAccessDeniedHandler implements AccessDeniedFailureResponder {

    private final ObjectMapper objectMapper;

    public ApiAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), new ApiErrorResponse(
                ErrorCode.ACCESS_DENIED.name(),
                "access denied",
                RequestIdSanitizer.sanitize(request.getHeader("X-Request-Id")),
                false
        ));
    }
}
