package com.fraudguard.service;

import com.fraudguard.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

// Exposes the outbox backlog: events written to the database but not yet published to Kafka.
// It normally sits near 0. If Kafka is down it climbs, and it drains again once Kafka is back.
// The count query runs each time Prometheus scrapes (every 10 s), which is cheap.
@Component
public class OutboxMetrics {

    public OutboxMetrics(MeterRegistry registry, OutboxEventRepository repository) {
        Gauge.builder("fraudguard.outbox.pending", repository, OutboxEventRepository::countByPublishedAtIsNull)
                .description("Outbox events not yet published to Kafka")
                .register(registry);
    }
}
