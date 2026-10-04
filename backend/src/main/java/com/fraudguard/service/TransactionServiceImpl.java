package com.fraudguard.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraudguard.config.KafkaConfig;
import com.fraudguard.dto.PageResponseDTO;
import com.fraudguard.dto.ReviewResponseDTO;
import com.fraudguard.dto.TransactionDetailDTO;
import com.fraudguard.dto.TransactionEvent;
import com.fraudguard.dto.TransactionRequestDTO;
import com.fraudguard.dto.TransactionResponseDTO;
import com.fraudguard.dto.TransactionSearchCriteria;
import com.fraudguard.dto.TransactionStatusUpdateDTO;
import com.fraudguard.dto.TransactionSummaryDTO;
import com.fraudguard.entity.AnalystReview;
import com.fraudguard.entity.OutboxEvent;
import com.fraudguard.entity.ReviewState;
import com.fraudguard.entity.Transaction;
import com.fraudguard.entity.TransactionStatus;
import com.fraudguard.exception.IdempotencyConflictException;
import com.fraudguard.exception.ResourceNotFoundException;
import com.fraudguard.repository.AnalystReviewRepository;
import com.fraudguard.repository.OutboxEventRepository;
import com.fraudguard.repository.TransactionRepository;
import com.fraudguard.repository.TransactionSpecifications;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service              // Spring creates one instance and injects it where needed
@Slf4j                // Lombok gives us a ready-made "log" variable
@RequiredArgsConstructor   // Lombok writes a constructor for the final fields (Spring injects them)
public class TransactionServiceImpl implements TransactionService {

    private static final int MAX_PAGE_SIZE = 100;

    private final TransactionRepository repository;
    private final OutboxEventRepository outboxRepository;     // the outbox table
    private final AnalystReviewRepository reviewRepository;   // NEW: analyst actions
    private final ObjectMapper objectMapper;                  // turns events into JSON text, and stored JSON back
    private final TransactionTemplate transactionTemplate;    // lets us open a transaction in code

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
            // ONE database transaction. The transaction row and its outbox row are saved
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

    // NEW: the dashboard list. Newest transactions first; the id breaks ties so paging is stable.
    @Override
    @Transactional(readOnly = true)
    public PageResponseDTO<TransactionResponseDTO> search(TransactionSearchCriteria criteria, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(Math.max(page, 0), safeSize,
                Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id")));
        Page<TransactionResponseDTO> result = repository
                .findAll(TransactionSpecifications.matching(criteria), pageable)
                .map(TransactionResponseDTO::from);
        return PageResponseDTO.from(result);
    }

    // NEW: numbers for the dashboard header
    @Override
    @Transactional(readOnly = true)
    public TransactionSummaryDTO summary() {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (TransactionStatus status : TransactionStatus.values()) {
            byStatus.put(status.name(), repository.countByStatus(status));
        }
        Map<String, Long> byReviewState = new LinkedHashMap<>();
        for (ReviewState state : ReviewState.values()) {
            byReviewState.put(state.name(), repository.countByReviewState(state));
        }
        long awaitingReview = repository.countByStatusInAndReviewState(
                List.of(TransactionStatus.REVIEW, TransactionStatus.BLOCKED), ReviewState.UNREVIEWED);
        return new TransactionSummaryDTO(repository.count(), awaitingReview, byStatus, byReviewState);
    }

    // NEW: the detail screen: transaction + stored decision inputs + review history
    @Override
    @Transactional(readOnly = true)
    public TransactionDetailDTO getDetail(UUID id) {
        Transaction transaction = findOrThrow(id);
        List<ReviewResponseDTO> reviews = reviewRepository.findByTransactionIdOrderByCreatedAtDesc(id)
                .stream().map(ReviewResponseDTO::from).toList();
        return new TransactionDetailDTO(TransactionResponseDTO.from(transaction),
                parseJson(transaction.getDecisionInputs()), reviews);
    }

    // NEW: records an analyst action. The action row and the new review state are saved together.
    // The model's decision (status, reason codes, scores) is NOT touched.
    @Override
    @Transactional
    public ReviewResponseDTO review(UUID id, TransactionStatusUpdateDTO request) {
        Transaction transaction = findOrThrow(id);

        AnalystReview review = new AnalystReview();
        review.setTransactionId(id);
        review.setAction(request.action());
        review.setNote(request.note() == null || request.note().isBlank() ? null : request.note().trim());
        review.setReviewer(request.reviewer().trim());
        AnalystReview saved = reviewRepository.save(review);

        transaction.setReviewState(request.action().toState());   // the latest action defines the current state
        repository.save(transaction);

        log.info("analyst review transactionId={} action={} reviewer={}", id, request.action(), saved.getReviewer());
        return ReviewResponseDTO.from(saved);
    }

    private Transaction findOrThrow(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction " + id + " not found"));
    }

    // Reads a stored JSON text column back into a JSON tree for the API (null if empty or unreadable)
    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            log.warn("stored decision inputs are not valid JSON: {}", e.getMessage());
            return null;
        }
    }

    // Builds the outbox row for a saved transaction. The OutboxRelay delivers it to Kafka later.
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