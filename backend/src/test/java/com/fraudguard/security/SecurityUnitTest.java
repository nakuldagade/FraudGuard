package com.fraudguard.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SecurityUnitTest {

    @Test
    void rateLimiterAllowsFiveThenBlocksThenRecoversAfterAMinute() {
        AtomicLong now = new AtomicLong(1_000_000);
        LoginRateLimiter limiter = new LoginRateLimiter(5, now::get);
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("1.2.3.4")).isTrue();
        }
        assertThat(limiter.tryAcquire("1.2.3.4")).isFalse();
        assertThat(limiter.tryAcquire("5.6.7.8")).isTrue();       // other addresses are separate
        now.addAndGet(60_001);
        assertThat(limiter.tryAcquire("1.2.3.4")).isTrue();       // the window has moved on
    }

    private static SecurityProperties props(String secret) {
        SecurityProperties p = new SecurityProperties();
        p.setJwtSecret(secret);
        return p;
    }

    @Test
    void shortSecretIsRefused() {
        assertThatThrownBy(() -> new JwtService(props("too-short"), new MockEnvironment().withProperty("x", "y")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
    }

    @Test
    void missingSecretIsRefusedOutsideTheLocalProfile() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertThatThrownBy(() -> new JwtService(props(""), prod))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("FRAUDGUARD_JWT_SECRET");
    }

    @Test
    void missingSecretOnTheLocalProfileGeneratesARandomOne() {
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");
        JwtService a = new JwtService(props(""), local);
        JwtService b = new JwtService(props(""), local);
        String token = a.issue("u", "ANALYST");
        assertThat(a.decoder().decode(token).getSubject()).isEqualTo("u");
        // a different instance has a different random secret, so it cannot read the first one's tokens
        assertThatThrownBy(() -> b.decoder().decode(token)).isInstanceOf(Exception.class);
    }
}
