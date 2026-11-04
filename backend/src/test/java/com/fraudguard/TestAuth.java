package com.fraudguard;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

// Test helpers for the secured API. get()/post() attach sensible default credentials so older tests keep
// reading naturally: the ingestion route gets the test API key, every other route gets an ANALYST token.
// A test that needs another identity adds .with(admin("x")) or .with(analyst("y")), which replaces the default.
public final class TestAuth {

    public static final String INGEST_KEY = "test-ingest-key";   // matches src/test/resources/application-test.yml

    private TestAuth() {
    }

    public static RequestPostProcessor analyst(String username) {
        return jwt().jwt(j -> j.subject(username).claim("role", "ANALYST"))
                .authorities(new SimpleGrantedAuthority("ROLE_ANALYST"));
    }

    public static RequestPostProcessor admin(String username) {
        return jwt().jwt(j -> j.subject(username).claim("role", "ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    public static MockHttpServletRequestBuilder get(String url, Object... uriVars) {
        return MockMvcRequestBuilders.get(url, uriVars).with(analyst("test-analyst"));
    }

    public static MockHttpServletRequestBuilder post(String url, Object... uriVars) {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.post(url, uriVars);
        return "/api/transactions".equals(url)
                ? request.header("X-API-Key", INGEST_KEY)
                : request.with(analyst("test-analyst"));
    }
}
