package com.fraudguard.service;

import com.fraudguard.entity.OutboxEvent;
import com.fraudguard.repository.OutboxEventRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxRelay {

    private final OutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    // Runs 200 ms after the previous run finished, for as long as the app is up.
    // @Transactional: the "published_at" updates are saved together when the method ends.
    //
    // Performance (Phase 11): the whole batch is handed to the Kafka producer first and the confirmations are
    // collected afterwards, so the producer can send many messages in one network round trip. Waiting for each
    // message in turn (the old way) limited the relay to roughly 50 events per second.
    // Ordering: events are sent in creation order, and the producer keeps that order per partition. If one fails,
    // the events before it are marked as published and the rest are retried next round. Events after the failure
    // may already have reached Kafka, so they can be delivered twice; the consumer ignores a repeated eventId.
    @Scheduled(fixedDelay = 200)
    @Transactional
    public void publishPending() {
        List<OutboxEvent> pending = outboxRepository.findTop500ByPublishedAtIsNullOrderByCreatedAtAsc();
        if (pending.isEmpty()) {
            return;
        }

        List<CompletableFuture<?>> sent = new ArrayList<>(pending.size());
        for (OutboxEvent event : pending) {
            try {
                sent.add(kafkaTemplate.send(event.getTopic(), event.getMessageKey(), event.getPayload()));
            } catch (RuntimeException e) {
                // Kafka is unreachable (send gave up after max.block.ms): stop handing over more
                log.warn("outbox send failed for id={}, will retry: {}", event.getId(), e.getMessage());
                break;
            }
        }

        for (int i = 0; i < sent.size(); i++) {
            OutboxEvent event = pending.get(i);
            try {
                sent.get(i).get(10, TimeUnit.SECONDS);   // wait for Kafka to confirm this one
                event.setPublishedAt(Instant.now());
                log.debug("outbox event published id={} topic={}", event.getId(), event.getTopic());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();   // the app is shutting down: stop quietly
                return;
            } catch (Exception e) {
                // Kafka refused or lost the message. Leave this row (and the ones after it) unsent and retry next round.
                log.warn("outbox publish failed for id={}, will retry: {}", event.getId(), e.getMessage());
                return;
            }
        }
        log.debug("outbox published {} event(s)", sent.size());
    }
}
