package com.fraudguard.security;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.fraudguard.entity.AppUser;
import com.fraudguard.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Tag(name = "Authentication", description = "Log in to get a JWT for the analyst routes")
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(@NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 200) String password) {
    }

    public record LoginResponse(String accessToken, long expiresInSeconds, String username, String role) {
    }

    public record MeResponse(String username, String role) {
    }

    // Same message for "no such user" and "wrong password", so the API can't be used to find valid usernames
    private static final String BAD_LOGIN = "Invalid username or password";

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwtService;
    private final LoginRateLimiter rateLimiter;
    private final MeterRegistry metrics;
    private final String dummyHash;   // checked when the user doesn't exist, so both cases take equally long

    public AuthController(UserRepository users, PasswordEncoder encoder, JwtService jwtService,
                          LoginRateLimiter rateLimiter, MeterRegistry metrics) {
        this.users = users;
        this.encoder = encoder;
        this.jwtService = jwtService;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        this.dummyHash = encoder.encode("not-a-real-password");
    }

    // POST /api/auth/login. Passwords and tokens are never logged.
    @Operation(summary = "Log in", description = "Returns a bearer token (60 minutes). Limited to 5 attempts per minute per IP.")
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        if (!rateLimiter.tryAcquire(http.getRemoteAddr())) {
            count("rate_limited");
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many login attempts. Try again in a minute.");
        }
        AppUser user = users.findByUsername(request.username().trim()).filter(AppUser::isEnabled).orElse(null);
        boolean ok = encoder.matches(request.password(), user != null ? user.getPasswordHash() : dummyHash)
                && user != null;
        if (!ok) {
            count("failure");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, BAD_LOGIN);
        }
        count("success");
        String token = jwtService.issue(user.getUsername(), user.getRole().name());
        return new LoginResponse(token, jwtService.ttl().toSeconds(), user.getUsername(), user.getRole().name());
    }

    // GET /api/auth/me: who am I? (the dashboard uses it to restore its header)
    @Operation(summary = "Who am I?", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/me")
    public MeResponse me(Authentication authentication) {
        String role = authentication.getAuthorities().stream()
                .map(a -> a.getAuthority().replaceFirst("^ROLE_", "")).findFirst().orElse("");
        return new MeResponse(authentication.getName(), role);
    }

    private void count(String outcome) {
        metrics.counter("fraudguard.auth.logins", "outcome", outcome).increment();
    }
}
