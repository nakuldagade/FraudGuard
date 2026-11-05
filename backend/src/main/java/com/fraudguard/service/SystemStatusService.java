package com.fraudguard.service;

import com.fraudguard.dto.SystemStatusDTO;
import com.fraudguard.entity.TransactionStatus;
import com.fraudguard.repository.OutboxEventRepository;
import com.fraudguard.repository.TransactionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

// What the admin Operations tab shows at the top: is each dependency reachable, is the ML circuit open, how big are the
// backlogs. Each check is independent and never throws, so one failing dependency does not hide the others.
// (The public /actuator/health needs the METRICS login, so it cannot be used from an admin's browser session.)
@Service
@Slf4j
public class SystemStatusService {

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final MlServiceClient mlClient;
    private final OutboxEventRepository outbox;
    private final TransactionRepository transactions;
    private final long stuckAfterSeconds;

    public SystemStatusService(JdbcTemplate jdbc, StringRedisTemplate redis, MlServiceClient mlClient,
                               OutboxEventRepository outbox, TransactionRepository transactions,
                               @Value("${fraudguard.reliability.stuck-after-seconds:120}") long stuckAfterSeconds) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.mlClient = mlClient;
        this.outbox = outbox;
        this.transactions = transactions;
        this.stuckAfterSeconds = stuckAfterSeconds;
    }

    public SystemStatusDTO status() {
        return new SystemStatusDTO(
                up(() -> jdbc.queryForObject("select 1", Integer.class)),
                up(() -> redis.execute((RedisCallback<String>) connection -> connection.ping())),
                mlClient.circuitState(),
                count(outbox::countByPublishedAtIsNull),
                count(() -> transactions.countByStatusAndCreatedAtBefore(
                        TransactionStatus.PENDING, Instant.now().minus(Duration.ofSeconds(stuckAfterSeconds)))),
                stuckAfterSeconds,
                Instant.now());
    }

    private static String up(Supplier<Object> check) {
        try {
            return check.get() != null ? "UP" : "DOWN";
        } catch (RuntimeException e) {
            log.debug("status check failed: {}", e.getMessage());
            return "DOWN";
        }
    }

    private static Long count(Supplier<Long> query) {
        try {
            return query.get();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
