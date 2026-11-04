package com.fraudguard.security;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

// Allows N login attempts per minute per client IP (sliding window), to slow down password guessing.
// In memory, so it is per backend instance and resets on restart: fine for one demo instance. A real
// deployment behind a proxy would key on the forwarded client address and use Redis.
@Component
public class LoginRateLimiter {

    private static final long WINDOW_MILLIS = 60_000;

    private final int maxAttempts;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Deque<Long>> attempts = new ConcurrentHashMap<>();

    @Autowired
    public LoginRateLimiter(SecurityProperties properties) {
        this(properties.getLoginAttemptsPerMinute(), System::currentTimeMillis);
    }

    LoginRateLimiter(int maxAttempts, LongSupplier clock) {
        this.maxAttempts = maxAttempts;
        this.clock = clock;
    }

    /** Returns true if this attempt is allowed (and records it), false if the IP is over the limit. */
    public boolean tryAcquire(String clientIp) {
        long now = clock.getAsLong();
        Deque<Long> window = attempts.computeIfAbsent(clientIp, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_MILLIS) {
                window.pollFirst();
            }
            if (window.size() >= maxAttempts) {
                return false;
            }
            window.addLast(now);
        }
        if (attempts.size() > 10_000) {              // keep memory bounded: drop IPs whose window is empty
            attempts.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    return e.getValue().isEmpty() || now - e.getValue().peekLast() >= WINDOW_MILLIS;
                }
            });
        }
        return true;
    }
}
