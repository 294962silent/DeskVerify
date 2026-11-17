package com.echomind.saas.identity.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;

@FunctionalInterface
public interface AccessDeniedFailureResponder {

    void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception
    ) throws IOException;
}
