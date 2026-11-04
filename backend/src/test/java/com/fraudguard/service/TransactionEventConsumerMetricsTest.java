package com.fraudguard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fraudguard.decision.Decision;
import com.fraudguard.decision.DecisionEngine;
import com.fraudguard.dto.TransactionEvent;
import com.fraudguard.dto.VelocityFeatures;
import com.fraudguard.entity.Transaction;
import com.fraudguard.entity.TransactionStatus;
import com.fraudguard.entity.TransactionType;
import com.fraudguard.repository.TransactionRepository;
import com.fraudguard.rules.RiskAssessment;
import com.fraudguard.rules.RuleEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// No Spring, no Docker: the consumer is built by hand with mocks, and a SimpleMeterRegistry records the metrics.
class TransactionEventConsumerMetricsTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final TransactionRepository repository = mock(TransactionRepository.class);
    private final TransactionCacheService cache = mock(TransactionCacheService.class);
    private final RuleEngine rules = mock(RuleEngine.class);
    private final MlServiceClient ml = mock(MlServiceClient.class);
    private final DecisionEngine decisions = mock(DecisionEngine.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private TransactionEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new TransactionEventConsumer(repository, mapper, cache, rules, ml, decisions, registry);
    }

    private Transaction transaction() {
        Transaction t = new Transaction();
        t.setStatus(TransactionStatus.PENDING);
        t.setCreatedAt(Instant.now().minusSeconds(2));     // accepted two seconds ago
        return t;
    }

    private String message(UUID eventId, UUID transactionId) throws Exception {
        TransactionEvent event = new TransactionEvent(eventId, TransactionEvent.TYPE_CREATED,
                TransactionEvent.SCHEMA_VERSION, Instant.now(),
                new TransactionEvent.Payload(transactionId, "acc-1", "m-1", TransactionType.PAYMENT,
                        new BigDecimal("10.00"), "INR", "d-1", "IN", Instant.now()));
        return mapper.writeValueAsString(event);
    }

    private void stubDecision(TransactionStatus status, List<String> reasons, boolean mlScored) {
        when(cache.recordAndComputeFeatures(any())).thenReturn(mock(VelocityFeatures.class));
        when(ml.score(any(), any())).thenReturn(mlScored
                ? Optional.of(new com.fraudguard.dto.MlScoreResponse("t", 0.3, "m-v1", "1")) : Optional.empty());
        when(rules.assess(any(), any())).thenReturn(new RiskAssessment(0, TransactionStatus.APPROVED, List.of()));
        when(decisions.decide(any(), any(), any()))
                .thenReturn(new Decision(status, reasons, "why", "policy-v1", null));
    }

    @Test
    void decidedEventCountsDecisionReasonCodesAndPipelineLatency() throws Exception {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(transaction()));
        stubDecision(TransactionStatus.REVIEW, List.of("HIGH_VELOCITY", "ML_ANOMALY_ELEVATED"), true);

        consumer.onTransactionRaw(message(UUID.randomUUID(), id));

        assertThat(registry.get("fraudguard.event.processing").tag("outcome", "decided").timer().count()).isEqualTo(1);
        assertThat(registry.get("fraudguard.decisions")
                .tags("status", "REVIEW", "policy", "policy-v1", "ml", "scored").counter().count()).isEqualTo(1);
        assertThat(registry.get("fraudguard.reason.codes").tag("code", "HIGH_VELOCITY").counter().count()).isEqualTo(1);
        assertThat(registry.get("fraudguard.reason.codes").tag("code", "ML_ANOMALY_ELEVATED").counter().count()).isEqualTo(1);
        var latency = registry.get("fraudguard.pipeline.latency").timer();
        assertThat(latency.count()).isEqualTo(1);
        assertThat(latency.totalTime(java.util.concurrent.TimeUnit.SECONDS)).isGreaterThanOrEqualTo(2.0);
    }

    @Test
    void rulesOnlyDecisionIsTaggedRulesOnly() throws Exception {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(transaction()));
        stubDecision(TransactionStatus.APPROVED, List.of("ML_UNAVAILABLE"), false);

        consumer.onTransactionRaw(message(UUID.randomUUID(), id));

        assertThat(registry.get("fraudguard.decisions").tags("status", "APPROVED", "ml", "rules_only")
                .counter().count()).isEqualTo(1);
    }

    @Test
    void alreadyAppliedEventIsCountedAsDuplicateAndNotDecidedAgain() throws Exception {
        UUID id = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Transaction t = transaction();
        t.setProcessedEventId(eventId);                      // this exact event was applied before
        when(repository.findById(id)).thenReturn(Optional.of(t));

        consumer.onTransactionRaw(message(eventId, id));

        assertThat(registry.get("fraudguard.event.processing").tag("outcome", "duplicate").timer().count()).isEqualTo(1);
        assertThat(registry.find("fraudguard.decisions").counters()).isEmpty();
        assertThat(registry.find("fraudguard.pipeline.latency").timers()).isEmpty();
    }

    @Test
    void failureIsCountedAsErrorAndRethrown() throws Exception {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());     // row missing: a retryable failure

        assertThatThrownBy(() -> consumer.onTransactionRaw(message(UUID.randomUUID(), id)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(registry.get("fraudguard.event.processing").tag("outcome", "error").timer().count()).isEqualTo(1);
    }

    @Test
    void malformedMessageIsCountedAsError() {
        assertThatThrownBy(() -> consumer.onTransactionRaw("not json"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(registry.get("fraudguard.event.processing").tag("outcome", "error").timer().count()).isEqualTo(1);
    }
}
