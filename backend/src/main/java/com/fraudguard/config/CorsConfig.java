package com.fraudguard.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// Browsers block a web page from calling an API on a different address (origin) unless the API
// says it's allowed. The React dev server runs on http://localhost:5173 and the API on :8080,
// so the API must explicitly allow that origin. This is CORS.
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    // The allowed origins come from application.yml (fraudguard.cors.allowed-origins), comma-separated
    public CorsConfig(@Value("${fraudguard.cors.allowed-origins:http://localhost:5173}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("Content-Type", "Idempotency-Key")
                .maxAge(3600);   // browsers may remember the answer for an hour
    }
}