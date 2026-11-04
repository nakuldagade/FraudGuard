package com.fraudguard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fraudguard.entity.TransactionStatus;
import com.fraudguard.repository.OutboxEventRepository;
import com.fraudguard.repository.TransactionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;

// The two backlog gauges read their numbers from the repositories on every scrape.
class StuckPendingMetricsTest {

    @Test
    void stuckPendingGaugeReportsTheRepositoryCount() {
        TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.countByStatusAndCreatedAtBefore(eq(TransactionStatus.PENDING), any(Instant.class)))
                .thenReturn(3L, 0L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new StuckPendingMetrics(registry, repo, 120);

        assertThat(registry.get("fraudguard.transactions.stuck.pending").gauge().value()).isEqualTo(3.0);
        assertThat(registry.get("fraudguard.transactions.stuck.pending").gauge().value()).isEqualTo(0.0);
    }

    @Test
    void outboxGaugeReportsTheUnpublishedCount() {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        when(repo.countByPublishedAtIsNull()).thenReturn(7L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new OutboxMetrics(registry, repo);

        assertThat(registry.get("fraudguard.outbox.pending").gauge().value()).isEqualTo(7.0);
    }
}
