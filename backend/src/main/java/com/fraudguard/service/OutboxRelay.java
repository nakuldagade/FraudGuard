package com.fraudguard.service;

import com.fraudguard.entity.OutboxEvent;
import com.fraudguard.repository.OutboxEventRepository;
import java.time.Instant;
import java.util.List;
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

    // Runs 1 second after the previous run finished, for as long as the app is up.
    // @Transactional: the "published_at" updates are saved together when the method ends.
    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void publishPending() {
        List<OutboxEvent> pending = outboxRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();

        for (OutboxEvent event : pending) {
            try {
                // Send and WAIT for Kafka to confirm (up to 10s) before marking it as delivered
                kafkaTemplate.send(event.getTopic(), event.getMessageKey(), event.getPayload())
                        .get(10, TimeUnit.SECONDS);
                event.setPublishedAt(Instant.now());
                log.info("outbox event published id={} topic={}", event.getId(), event.getTopic());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();   // the app is shutting down: stop quietly
                return;
            } catch (Exception e) {
                // Kafka is unreachable or refused the message. Leave this row (and the ones after it)
                // unsent and try again on the next round. Stopping here keeps the original order.
                log.warn("outbox publish failed for id={}, will retry: {}", event.getId(), e.getMessage());
                return;
            }
        }
    }
}