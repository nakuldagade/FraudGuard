package com.fraudguard.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraudguard.config.KafkaConfig;
import com.fraudguard.dto.TransactionEvent;
import com.fraudguard.dto.TransactionRequestDTO;
import com.fraudguard.dto.TransactionResponseDTO;
import com.fraudguard.entity.OutboxEvent;
import com.fraudguard.entity.Transaction;
import com.fraudguard.entity.TransactionStatus;
import com.fraudguard.exception.IdempotencyConflictException;
import com.fraudguard.exception.ResourceNotFoundException;
import com.fraudguard.repository.OutboxEventRepository;
import com.fraudguard.repository.TransactionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service              // Spring creates one instance and injects it where needed
@Slf4j                // Lombok gives us a ready-made "log" variable
@RequiredArgsConstructor   // Lombok writes a constructor for the final fields (Spring injects them)
public class TransactionServiceImpl implements TransactionService {

    private final TransactionRepository repository;
    private final OutboxEventRepository outboxRepository;     // NEW: the outbox table
    private final ObjectMapper objectMapper;                  // turns the event into JSON text
    private final TransactionTemplate transactionTemplate;    // NEW: lets us open a transaction in code

    /*
     * This method itself is NOT @Transactional, because of the duplicate-key race described below.
     * Instead, the two inserts that must succeed or fail TOGETHER run inside transactionTemplate.
     */
    @Override
    public CreateResult create(String idempotencyKey, TransactionRequestDTO request) {
        String hash = fingerprint(request);

        // 1) Have we seen this key before? Then this is a retry.
        Optional<Transaction> existing = repository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), hash);
        }

        // 2) New key: build the entity and save it.
        Transaction transaction = toEntity(idempotencyKey, hash, request);
        try {
            // NEW: ONE database transaction. The transaction row and its outbox row are saved
            // together; if either insert fails, both are rolled back. No event is ever lost
            // and no event is ever sent for a transaction that wasn't saved.
            Transaction saved = transactionTemplate.execute(status -> {
                Transaction t = repository.saveAndFlush(transaction);
                outboxRepository.save(toOutboxEvent(t));
                return t;
            });
            log.info("transaction created id={} accountId={} status={}",
                    saved.getId(), saved.getAccountId(), saved.getStatus());
            return new CreateResult(TransactionResponseDTO.from(saved), true);
        } catch (DataIntegrityViolationException e) {
            // 3) Lost a race with an identical request (the UNIQUE constraint rejected our insert,
            //    and everything we did was rolled back). Load the winner and replay it.
            Transaction winner = repository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> e);
            return replay(winner, hash);
        }
    }

    @Override
    @Transactional(readOnly = true)   // read-only hint lets the database optimise
    public TransactionResponseDTO getById(UUID id) {
        return repository.findById(id)
                .map(TransactionResponseDTO::from)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction " + id + " not found"));
    }

    // NEW: builds the outbox row for a saved transaction. The OutboxRelay delivers it to Kafka later.
    // The message key is the accountId: Kafka sends all messages with the same key to the same
    // partition, so events for one account are processed in the order they were created.
    private OutboxEvent toOutboxEvent(Transaction t) {
        TransactionEvent event = TransactionEvent.created(t);
        OutboxEvent outbox = new OutboxEvent();
        outbox.setId(event.eventId());
        outbox.setAggregateId(t.getId());
        outbox.setTopic(KafkaConfig.TOPIC_RAW);
        outbox.setMessageKey(t.getAccountId());
        outbox.setPayload(toJson(event));
        outbox.setCreatedAt(Instant.now());
        return outbox;
    }

    private String toJson(TransactionEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize event " + event.eventId(), e);
        }
    }

    // Same key + same body = harmless retry.  Same key + different body = client bug -> 409.
    private CreateResult replay(Transaction existing, String hash) {
        if (!existing.getRequestHash().equals(hash)) {
            throw new IdempotencyConflictException(
                    "Idempotency-Key was already used with a different request body");
        }
        log.info("idempotent replay id={}", existing.getId());
        return new CreateResult(TransactionResponseDTO.from(existing), false);
    }

    // Copies the validated request into a new entity. New transactions always start as PENDING.
    private Transaction toEntity(String idempotencyKey, String hash, TransactionRequestDTO r) {
        Transaction t = new Transaction();
        t.setIdempotencyKey(idempotencyKey);
        t.setRequestHash(hash);
        t.setAccountId(r.accountId());
        t.setMerchantId(r.merchantId());
        t.setTransactionType(r.transactionType());
        t.setAmount(r.amount());
        t.setCurrency(r.currency());
        t.setDeviceId(r.deviceId());
        t.setCountry(r.country());
        // If the client didn't say when it happened, use "now"
        t.setOccurredAt(r.occurredAt() != null ? r.occurredAt() : Instant.now());
        t.setStatus(TransactionStatus.PENDING);
        return t;
    }

    // Builds a SHA-256 fingerprint of the request, so we can tell a genuine retry
    // from someone reusing a key with different data.
    private static String fingerprint(TransactionRequestDTO r) {
        String canonical = String.join("|",
                r.accountId(),
                nullToEmpty(r.merchantId()),
                r.transactionType().name(),
                // Normalise the amount so 10.50 and 10.5 produce the same fingerprint
                r.amount().stripTrailingZeros().toPlainString(),
                r.currency(),
                nullToEmpty(r.deviceId()),
                nullToEmpty(r.country()),
                r.occurredAt() == null ? "" : r.occurredAt().toString());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);   // 64 hex characters
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}