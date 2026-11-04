package com.fraudguard.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

// Ingestion (POST /api/transactions) is called by other systems, not by people, so it uses a service
// API key in the X-API-Key header instead of a login. A matching key gives the request the INGEST role.
// A missing or wrong key just means "not authenticated", and the security chain answers 401.
// Not a @Bean on purpose: Spring Boot would also register a bean filter for every request.
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";
    public static final String ROLE = "ROLE_INGEST";

    private final List<byte[]> keyDigests;

    public ApiKeyAuthenticationFilter(List<String> keys) {
        this.keyDigests = keys.stream().filter(k -> k != null && !k.isBlank()).map(String::trim)
                .map(ApiKeyAuthenticationFilter::sha256).toList();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !("POST".equalsIgnoreCase(request.getMethod()) && ("/api/transactions".equals(path) || "/api/transactions/".equals(path)));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader(HEADER);
        if (supplied != null && matches(supplied)) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "ingest-client", null, List.of(new SimpleGrantedAuthority(ROLE))));
        }
        chain.doFilter(request, response);
    }

    // Compare SHA-256 digests with MessageDigest.isEqual (constant time): the response time reveals
    // nothing about how much of a guessed key was right, and the digests always have the same length.
    private boolean matches(String supplied) {
        byte[] digest = sha256(supplied.trim());
        boolean match = false;
        for (byte[] known : keyDigests) {          // no early exit: every key is always checked
            match |= MessageDigest.isEqual(known, digest);
        }
        return match;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

}
