package com.fraudguard.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraudguard.config.KafkaConfig;
import com.fraudguard.dto.TransactionEvent;
import com.fraudguard.dto.VelocityFeatures;
import com.fraudguard.entity.Transaction;
import com.fraudguard.entity.TransactionStatus;
import com.fraudguard.repository.TransactionRepository;
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
    private final TransactionCacheService cacheService;   // NEW: Redis velocity features

    // Spring calls this method for every message that arrives on transactions.raw.
    // @Transactional: the database changes below are saved together, or not at all.
    //
    // If this method throws, KafkaConfig's error handler takes over:
    //   - IllegalArgumentException (bad message)  -> straight to the dead-letter topic
    //   - any other exception (e.g. DB or Redis down) -> retried after 1s, 2s, 4s, then dead-lettered
    @KafkaListener(topics = KafkaConfig.TOPIC_RAW)
    @Transactional
    public void onTransactionRaw(String message) {
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
            return;
        }

        // NEW: how active has this account been recently? Phases 5-7 (rules, ML score, decision)
        // will use these values; for now we only log them.
        // Retries are safe: Redis remembers the answer per transaction, so nothing is counted twice.
        VelocityFeatures features = cacheService.recordAndComputeFeatures(event.payload());
        log.info("velocity features transactionId={} {}", transactionId, features);

        // PLACEHOLDER DECISION: everything is approved for now.
        // Phases 5-7 replace this line with rules, an ML score and a real decision.
        transaction.setStatus(TransactionStatus.APPROVED);
        transaction.setProcessedEventId(event.eventId());   // remember which event we applied
        repository.save(transaction);

        log.info("transaction processed id={} status={} eventId={}",
                transactionId, transaction.getStatus(), event.eventId());
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