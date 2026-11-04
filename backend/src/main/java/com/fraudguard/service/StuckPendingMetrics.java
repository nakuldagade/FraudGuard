package com.fraudguard.service;

import com.fraudguard.entity.TransactionStatus;
import com.fraudguard.repository.TransactionRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Exposes how many transactions have been PENDING for too long. This is the "silent failure" signal:
// if Redis or the database is down longer than the Kafka retry window, the event is dead-lettered
// and the transaction would otherwise stay PENDING forever with nobody noticing.
@Component
public class StuckPendingMetrics {

    public StuckPendingMetrics(MeterRegistry registry, TransactionRepository repository,
                               @Value("${fraudguard.reliability.stuck-after-seconds:120}") long stuckAfterSeconds) {
        Gauge.builder("fraudguard.transactions.stuck.pending", repository,
                        r -> r.countByStatusAndCreatedAtBefore(TransactionStatus.PENDING,
                                Instant.now().minus(Duration.ofSeconds(stuckAfterSeconds))))
                .description("Transactions PENDING for longer than the stuck-after threshold")
                .register(registry);
    }
}
