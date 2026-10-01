package com.fraudguard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fraudguard.entity.OutboxEvent;
import com.fraudguard.repository.OutboxEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

// A plain unit test: the database and Kafka are replaced by fakes ("mocks").
class OutboxRelayTest {

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    private final OutboxRelay relay = new OutboxRelay(repository, kafkaTemplate);

    @Test
    void publishesPendingEventsAndMarksThemPublished() {
        OutboxEvent first = outboxEvent("acc-1");
        OutboxEvent second = outboxEvent("acc-2");
        when(repository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(first, second));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));   // Kafka says "stored"

        relay.publishPending();

        assertThat(first.getPublishedAt()).isNotNull();
        assertThat(second.getPublishedAt()).isNotNull();
    }

    @Test
    void kafkaOutageLeavesEventsPendingUntilKafkaIsBack() {
        OutboxEvent first = outboxEvent("acc-1");
        OutboxEvent second = outboxEvent("acc-2");
        when(repository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(first, second));

        // Round 1: Kafka is down
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));
        relay.publishPending();

        assertThat(first.getPublishedAt()).isNull();    // nothing was marked as sent...
        assertThat(second.getPublishedAt()).isNull();
        // ...and the relay gave up after the first failure instead of skipping ahead (keeps order)
        verify(kafkaTemplate, times(1)).send(anyString(), anyString(), anyString());

        // Round 2: Kafka is back, the same rows are picked up again
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        relay.publishPending();

        assertThat(first.getPublishedAt()).isNotNull();
        assertThat(second.getPublishedAt()).isNotNull();
    }

    private static OutboxEvent outboxEvent(String key) {
        OutboxEvent e = new OutboxEvent();
        e.setId(UUID.randomUUID());
        e.setAggregateId(UUID.randomUUID());
        e.setTopic("transactions.raw");
        e.setMessageKey(key);
        e.setPayload("{}");
        e.setCreatedAt(Instant.now());
        return e;
    }
}