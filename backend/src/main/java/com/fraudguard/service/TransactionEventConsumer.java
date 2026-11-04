package com.fraudguard.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraudguard.config.KafkaConfig;
import com.fraudguard.decision.Decision;
import com.fraudguard.decision.DecisionEngine;
import com.fraudguard.dto.MlScoreResponse;
import com.fraudguard.dto.TransactionEvent;
import com.fraudguard.dto.VelocityFeatures;
import com.fraudguard.entity.Transaction;
import com.fraudguard.repository.TransactionRepository;
import com.fraudguard.rules.RiskAssessment;
import com.fraudguard.rules.RuleEngine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class TransactionEventConsumer {

    private final TransactionRepository repository;
    private final ObjectMapper objectMapper;
    private final TransactionCacheService cacheService;   // Redis velocity features
    private final RuleEngine ruleEngine;                  // rules -> rule score + rule verdict
    private final MlServiceClient mlClient;               // ML anomaly score (optional)
    private final DecisionEngine decisionEngine;          // combines everything into the final decision
    private final MeterRegistry metrics;                  // NEW (Phase 10): counters and timers

    // Spring calls this method for every message that arrives on transactions.raw.
    // @Transactional: the database changes below are saved together, or not at all.
    //
    // If this method throws, KafkaConfig's error handler takes over:
    //   - IllegalArgumentException (bad message)  -> straight to the dead-letter topic
    //   - any other exception (e.g. DB or Redis down) -> retried after 1s, 2s, 4s, then dead-lettered
    @KafkaListener(topics = KafkaConfig.TOPIC_RAW)
    @Transactional
    public void onTransactionRaw(String message) {
        // Time every event and record how it ended: decided, duplicate, or error (it threw)
        Timer.Sample sample = Timer.start(metrics);
        String outcome = "error";
        try {
            outcome = process(message) ? "decided" : "duplicate";
        } finally {
            sample.stop(Timer.builder("fraudguard.event.processing")
                    .description("Time to process one transactions.raw event")
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(metrics));
        }
    }

    // Returns true if a decision was made, false if the event was a duplicate that was skipped.
    private boolean process(String message) {
        TransactionEvent event = parse(message);
        UUID transactionId = event.payload().transactionId();

        // A missing row throws a normal exception, so it is retried (it may still be arriving)
        Transaction transaction = repository.findById(transactionId)
                .orElseThrow(() -> new IllegalStateException(
                        "Transaction " + transactionId + " not found"));

        // Duplicate protection: Kafka guarantees "at least once" delivery, so the same event
        // can arrive twice. If this event was already applied, do nothing.
        if (event.eventId().equals(transaction.getProcessedEventId())) {
            log.info("duplicate event skipped eventId={} transactionId={}", event.eventId(), transactionId);
            return false;
        }

        // How active has this account been recently? (Redis; safe to retry, see Phase 4)
        VelocityFeatures features = cacheService.recordAndComputeFeatures(event.payload());
        log.info("velocity features transactionId={} {}", transactionId, features);

        // How unusual does the ML model find it? Never throws: empty if the service is down.
        Optional<MlScoreResponse> ml = mlClient.score(event.payload(), features);

        // What do the fraud rules say?
        RiskAssessment assessment = ruleEngine.assess(event.payload(), features);

        // The final decision, combining rules + ML + velocity features under one policy
        Decision decision = decisionEngine.decide(assessment, ml, features);

        // Store everything with the transaction, so the decision can always be explained later
        transaction.setStatus(decision.status());                              // the final decision
        transaction.setRiskScore(assessment.score());                          // the rule score
        transaction.setRuleHits(assessment.hits());
        ml.ifPresent(result -> {
            transaction.setAnomalyScore(result.anomalyScore());
            transaction.setModelVersion(result.modelVersion());
        });
        transaction.setPolicyVersion(decision.policyVersion());
        transaction.setReasonCodes(String.join(",", decision.reasonCodes()));
        transaction.setDecisionExplanation(decision.explanation());
        transaction.setDecisionInputs(toJson(decision.inputs()));              // full snapshot
        transaction.setProcessedEventId(event.eventId());   // remember which event we applied
        repository.save(transaction);

        recordDecisionMetrics(transaction, decision, ml.isPresent());

        log.info("transaction decided id={} status={} reasons={} policy={} eventId={}",
                transactionId, decision.status(), decision.reasonCodes(), decision.policyVersion(),
                event.eventId());
        return true;
    }

    // Phase 10: what was decided, why, and how long the whole pipeline took.
    // (Counted just before the database transaction commits, so a rare commit failure
    // followed by a retry can count a decision twice. Fine for monitoring.)
    private void recordDecisionMetrics(Transaction transaction, Decision decision, boolean mlScored) {
        metrics.counter("fraudguard.decisions",
                "status", decision.status().name(),
                "policy", decision.policyVersion(),
                "ml", mlScored ? "scored" : "rules_only").increment();

        for (var code : decision.reasonCodes()) {
            metrics.counter("fraudguard.reason.codes", "code", String.valueOf(code)).increment();
        }

        // From the moment the API accepted the transaction until now. Includes any Kafka retries,
        // so an outage in Redis or the database shows up here as a latency spike.
        Duration latency = Duration.between(transaction.getCreatedAt(), Instant.now());
        Timer.builder("fraudguard.pipeline.latency")
                .description("Time from API acceptance to final decision")
                .publishPercentileHistogram()
                .register(metrics)
                .record(latency);
    }

    // Turns the decision snapshot into JSON text for the database column
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize the decision inputs", e);
        }
    }

    // Turns the JSON text into a TransactionEvent and checks it is something we understand.
    // Every failure here is an IllegalArgumentException = "this message can never succeed".
    private TransactionEvent parse(String message) {
        TransactionEvent event;
        try {
            event = objectMapper.readValue(message, TransactionEvent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Malformed event JSON", e);
        }
        if (event.eventId() == null || event.payload() == null || event.payload().transactionId() == null) {
            throw new IllegalArgumentException("Event is missing required fields");
        }
        // Versioning in action: refuse formats this consumer doesn't know about
        if (!TransactionEvent.TYPE_CREATED.equals(event.eventType())
                || event.schemaVersion() != TransactionEvent.SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported event: type=" + event.eventType()
                    + " schemaVersion=" + event.schemaVersion());
        }
        return event;
    }
}