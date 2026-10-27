package com.example.userservice.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;

final class InternalTokenFilter extends OncePerRequestFilter {
    private final byte[] expected;
    InternalTokenFilter(String token) {
        if (token.length() < 32) throw new IllegalArgumentException("INTERNAL_API_TOKEN must have at least 32 characters");
        expected = token.getBytes(StandardCharsets.UTF_8);
    }
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().equals("/actuator/health");
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader("X-Internal-Token");
        if (supplied == null || !MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(401, "Internal authentication required");
            return;
        }
        chain.doFilter(request, response);
    }
}
