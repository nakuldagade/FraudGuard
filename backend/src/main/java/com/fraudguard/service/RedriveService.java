package com.fraudguard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraudguard.entity.Transaction;
import com.fraudguard.entity.TransactionStatus;
import com.fraudguard.repository.OutboxEventRepository;
import com.fraudguard.repository.TransactionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Recovers transactions whose event was dead-lettered: writes a NEW outbox event (new eventId) for each
// stuck PENDING transaction, and the normal pipeline picks it up again.
// Safe to call repeatedly: decided transactions are no longer PENDING, and a transaction that already
// has an unsent outbox event is skipped.
@Service
@Slf4j
@RequiredArgsConstructor
public class RedriveService {

    public record Result(int requeued, int skipped) {
    }

    private final TransactionRepository repository;
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry metrics;

    @Transactional
    public Result redrivePending(Duration olderThan, int limit) {
        Instant cutoff = Instant.now().minus(olderThan);
        List<Transaction> stuck = repository.findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                TransactionStatus.PENDING, cutoff, PageRequest.of(0, limit));

        int requeued = 0;
        int skipped = 0;
        for (Transaction t : stuck) {
            if (outboxRepository.existsByAggregateIdAndPublishedAtIsNull(t.getId())) {
                skipped++;   // an event is already on its way
                continue;
            }
            outboxRepository.save(TransactionServiceImpl.buildOutboxEvent(t, objectMapper));
            requeued++;
        }
        metrics.counter("fraudguard.redrive.requeued").increment(requeued);
        log.info("redrive requeued={} skipped={} olderThan={}", requeued, skipped, olderThan);
        return new Result(requeued, skipped);
    }
}
