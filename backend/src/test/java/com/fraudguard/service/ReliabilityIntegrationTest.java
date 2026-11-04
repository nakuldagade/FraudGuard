package com.fraudguard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fraudguard.config.KafkaConfig;
import com.fraudguard.config.TestcontainersConfig;
import com.fraudguard.entity.Transaction;
import com.fraudguard.entity.TransactionStatus;
import com.fraudguard.entity.TransactionType;
import com.fraudguard.repository.OutboxEventRepository;
import com.fraudguard.repository.TransactionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;

// Failure recovery: a transaction whose event never reached the decision (dead-lettered) is
// recovered by the re-drive service, and the dead-letter topic is observable.
@SpringBootTest
@AutoConfigureMockMvc   // same setup as the other integration tests so they share one app + containers
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class ReliabilityIntegrationTest {

    @Autowired TransactionRepository repository;
    @Autowired OutboxEventRepository outboxRepository;
    @Autowired RedriveService redriveService;
    @Autowired DlqService dlqService;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired MeterRegistry metrics;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Test
    void stuckPendingTransactionGetsANewOutboxEventAndIsDecided() {
        Transaction t = stuckTransaction("acc-redrive-" + UUID.randomUUID());
        assertThat(outboxRepository.findByAggregateId(t.getId())).isEmpty();   // its event was "lost"

        RedriveService.Result first = redriveService.redrivePending(Duration.ofSeconds(60), 100);
        assertThat(first.requeued()).isGreaterThanOrEqualTo(1);
        assertThat(outboxRepository.findByAggregateId(t.getId())).hasSize(1);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(repository.findById(t.getId()).orElseThrow().getStatus())
                        .isEqualTo(TransactionStatus.APPROVED));

        // Idempotent: a decided transaction is no longer PENDING, so nothing is requeued for it
        redriveService.redrivePending(Duration.ofSeconds(60), 100);
        assertThat(outboxRepository.findByAggregateId(t.getId())).hasSize(1);
    }

    @Test
    void redriveSkipsATransactionThatAlreadyHasAnUnsentEvent() {
        Transaction t = stuckTransaction("acc-skip-" + UUID.randomUUID());
        // Create the unsent outbox row by hand, then stop the relay from sending it by checking at once
        outboxRepository.save(TransactionServiceImpl.buildOutboxEvent(t, objectMapper));
        // The relay may publish it within a second, so only assert the skip when it is still unsent
        if (outboxRepository.existsByAggregateIdAndPublishedAtIsNull(t.getId())) {
            RedriveService.Result r = redriveService.redrivePending(Duration.ofSeconds(60), 100);
            assertThat(r.skipped()).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void deadLetterSummaryShowsTheCauseOfAPoisonMessage() throws Exception {
        kafkaTemplate.send(KafkaConfig.TOPIC_RAW, "acc-poison", "not-json-" + UUID.randomUUID()).get();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            DlqService.Summary summary = dlqService.summarize();
            assertThat(summary.totalMessages()).isGreaterThanOrEqualTo(1);
            assertThat(summary.recentByException()).containsKey("java.lang.IllegalArgumentException");
        });
        assertThat(metrics.get("fraudguard.dlq.events").tag("cause", "IllegalArgumentException")
                .counter().count()).isGreaterThanOrEqualTo(1.0);
    }

    // A PENDING transaction with no outbox row, created two minutes ago
    private Transaction stuckTransaction(String account) {
        Transaction t = new Transaction();
        t.setIdempotencyKey("stuck-" + UUID.randomUUID());
        t.setRequestHash("h".repeat(64));
        t.setAccountId(account);
        t.setTransactionType(TransactionType.DEPOSIT);
        t.setAmount(new BigDecimal("12.50"));
        t.setCurrency("USD");
        t.setOccurredAt(java.time.Instant.now());
        t.setStatus(TransactionStatus.PENDING);
        t = repository.saveAndFlush(t);
        jdbc.update("update transactions set created_at = now() - interval '2 minutes' where id = ?", t.getId());
        return repository.findById(t.getId()).orElseThrow();
    }
}
