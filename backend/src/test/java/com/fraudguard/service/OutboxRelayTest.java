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
        when(repository.findTop500ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(first, second));
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
        when(repository.findTop500ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(first, second));

        // Round 1: Kafka is down
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));
        relay.publishPending();

        assertThat(first.getPublishedAt()).isNull();    // nothing was marked as sent...
        assertThat(second.getPublishedAt()).isNull();
        // (the whole batch was handed to Kafka together, and none of it was confirmed)
        verify(kafkaTemplate, times(2)).send(anyString(), anyString(), anyString());

        // Round 2: Kafka is back, the same rows are picked up again
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        relay.publishPending();

        assertThat(first.getPublishedAt()).isNotNull();
        assertThat(second.getPublishedAt()).isNotNull();
    }

    @Test
    void failureMidBatchMarksOnlyTheEventsBeforeIt() {
        OutboxEvent a = outboxEvent("acc-1");
        OutboxEvent b = outboxEvent("acc-2");
        OutboxEvent c = outboxEvent("acc-3");
        when(repository.findTop500ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(a, b, c));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("lost")))
                .thenReturn(CompletableFuture.completedFuture(null));

        relay.publishPending();

        assertThat(a.getPublishedAt()).isNotNull();
        assertThat(b.getPublishedAt()).isNull();    // failed: retried next round
        assertThat(c.getPublishedAt()).isNull();    // after the failure: also retried, so order is kept
    }

    @Test
    void synchronousSendFailureStopsHandingOverMoreEvents() {
        OutboxEvent a = outboxEvent("acc-1");
        OutboxEvent b = outboxEvent("acc-2");
        when(repository.findTop500ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(a, b));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenThrow(new RuntimeException("max.block.ms exceeded"));

        relay.publishPending();

        assertThat(a.getPublishedAt()).isNull();
        assertThat(b.getPublishedAt()).isNull();
        verify(kafkaTemplate, times(1)).send(anyString(), anyString(), anyString());   // did not block 5 s per event
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