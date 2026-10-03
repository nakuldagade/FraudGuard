package com.fraudguard.service;

import com.fraudguard.dto.MlScoreRequest;
import com.fraudguard.dto.MlScoreResponse;
import com.fraudguard.dto.TransactionEvent;
import com.fraudguard.dto.VelocityFeatures;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Asks the Python ML service for an anomaly score.
 *
 * Failure policy: scoring is OPTIONAL. If the service is disabled, down, slow or answers nonsense,
 * score() returns an empty Optional and the caller carries on with rules only.
 *   - Timeout: every call gives up after fraudguard.ml.timeout-ms.
 *   - Circuit breaker: after N failures in a row, calls are skipped for a while instead of each
 *     one waiting for the timeout. After that pause ONE trial call is allowed: success closes the
 *     circuit, another failure reopens it straight away.
 */
@Service
@Slf4j
public class MlServiceClient {

    private final boolean enabled;
    private final RestClient restClient;
    private final int failureThreshold;
    private final long openForMillis;
    private final LongSupplier clock;                 // milliseconds; replaceable so tests can control time

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long circuitOpenUntil = 0;       // while now < this, ML calls are skipped

    // Used by Spring: values come from application.yml (fraudguard.ml.*), with defaults
    @Autowired
    public MlServiceClient(
            @Value("${fraudguard.ml.enabled:true}") boolean enabled,
            @Value("${fraudguard.ml.base-url:http://localhost:8000}") String baseUrl,
            @Value("${fraudguard.ml.timeout-ms:1500}") long timeoutMillis,
            @Value("${fraudguard.ml.failure-threshold:3}") int failureThreshold,
            @Value("${fraudguard.ml.open-seconds:30}") long openSeconds) {
        this(enabled, baseUrl, Duration.ofMillis(timeoutMillis), failureThreshold,
                Duration.ofSeconds(openSeconds), System::currentTimeMillis);
    }

    // Used by tests, which pass their own clock
    MlServiceClient(boolean enabled, String baseUrl, Duration timeout, int failureThreshold,
                    Duration openFor, LongSupplier clock) {
        this.enabled = enabled;
        this.failureThreshold = failureThreshold;
        this.openForMillis = openFor.toMillis();
        this.clock = clock;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) timeout.toMillis());   // how long to wait to connect
        factory.setReadTimeout((int) timeout.toMillis());      // how long to wait for the answer
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** Returns the score, or empty if scoring isn't available right now. Never throws. */
    public Optional<MlScoreResponse> score(TransactionEvent.Payload payload, VelocityFeatures features) {
        if (!enabled) {
            return Optional.empty();
        }
        if (clock.getAsLong() < circuitOpenUntil) {
            log.debug("ML circuit open, skipping scoring for transactionId={}", payload.transactionId());
            return Optional.empty();
        }
        try {
            MlScoreResponse response = restClient.post()
                    .uri("/score")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(toRequest(payload, features))
                    .retrieve()                      // 4xx/5xx answers become exceptions
                    .body(MlScoreResponse.class);
            if (!isValid(response)) {
                throw new IllegalStateException("ML service returned an invalid response");
            }
            consecutiveFailures.set(0);              // a success closes the circuit
            return Optional.of(response);
        } catch (RuntimeException e) {
            recordFailure(e);
            return Optional.empty();
        }
    }

    private void recordFailure(RuntimeException e) {
        int failures = consecutiveFailures.incrementAndGet();
        log.warn("ML scoring unavailable ({} failures in a row): {}", failures, e.getMessage());
        if (failures >= failureThreshold) {
            // The counter is NOT reset here: once the pause is over, a single failed trial
            // call is enough to open the circuit again.
            circuitOpenUntil = clock.getAsLong() + openForMillis;
            log.warn("ML circuit opened: skipping ML scoring for {} seconds", openForMillis / 1000);
        }
    }

    private static boolean isValid(MlScoreResponse r) {
        return r != null
                && r.anomalyScore() != null
                && !r.anomalyScore().isNaN()
                && r.anomalyScore() >= 0.0 && r.anomalyScore() <= 1.0
                && r.modelVersion() != null && !r.modelVersion().isBlank();
    }

    // Converts our domain objects into the JSON shape the Python service expects
    private static MlScoreRequest toRequest(TransactionEvent.Payload p, VelocityFeatures f) {
        return new MlScoreRequest(
                p.transactionId().toString(),
                p.amount().doubleValue(),
                p.occurredAt().atZone(ZoneOffset.UTC).getHour(),
                f.transactionCount5m(),
                f.transactionCount1h(),
                f.historyCount(),
                f.averageAmount().doubleValue(),
                f.amountZScore(),
                f.secondsSincePreviousTransaction(),
                f.newDevice(),
                f.newMerchant(),
                f.countryChanged());
    }
}