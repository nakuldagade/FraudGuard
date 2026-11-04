package com.fraudguard.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

// Creates and checks login tokens: signed (HS256) JWTs holding the username, the role and an expiry.
// "Signed" means any change to the token (a different role, a later expiry) makes the signature fail.
@Service
@Slf4j
public class JwtService {

    private static final int MIN_SECRET_BYTES = 32;   // HS256 needs a 256-bit key

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Duration ttl;

    public JwtService(SecurityProperties properties, Environment environment) {
        String secret = properties.getJwtSecret();
        if (secret == null || secret.isBlank()) {
            // Fail closed: only the local profile may invent a secret. Anywhere else a missing secret is a bug.
            if (!environment.acceptsProfiles(org.springframework.core.env.Profiles.of("local"))) {
                throw new IllegalStateException(
                        "FRAUDGUARD_JWT_SECRET is required (at least 32 bytes) outside the local profile");
            }
            byte[] random = new byte[48];
            new SecureRandom().nextBytes(random);
            secret = Base64.getEncoder().encodeToString(random);
            log.warn("FRAUDGUARD_JWT_SECRET is not set: generated a random one. "
                    + "Login tokens will stop working when the backend restarts.");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("FRAUDGUARD_JWT_SECRET must be at least 32 bytes long");
        }
        SecretKey key = new SecretKeySpec(keyBytes, "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        // Zero clock skew: Spring's default accepts tokens up to 60 s after they expire
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(Duration.ZERO)));
        this.decoder = nimbus;
        this.ttl = Duration.ofMinutes(properties.getTokenTtlMinutes());
    }

    public Duration ttl() {
        return ttl;
    }

    public String issue(String username, String role) {
        return issue(username, role, Instant.now());
    }

    // Package-visible so tests can create tokens that are already expired
    String issue(String username, String role, Instant issuedAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(username)
                .claim("role", role)
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(ttl))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public JwtDecoder decoder() {
        return decoder;
    }
}
