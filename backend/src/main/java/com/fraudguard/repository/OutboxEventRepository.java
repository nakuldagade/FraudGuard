package com.fraudguard.repository;

import com.fraudguard.entity.OutboxEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    // Spring builds the query from the name: unsent rows (published_at IS NULL),
    // oldest first, at most 500 per round (was 50: that capped throughput near 50 events/s, see docs/performance.md).
    List<OutboxEvent> findTop500ByPublishedAtIsNullOrderByCreatedAtAsc();

    // Used by tests to find the outbox rows belonging to one transaction
    List<OutboxEvent> findByAggregateId(UUID aggregateId);

    // Phase 10: how many events are waiting to be published (the outbox backlog metric)
    long countByPublishedAtIsNull();

    // Phase 10: is an event for this transaction already waiting to be published? (re-drive skips those)
    boolean existsByAggregateIdAndPublishedAtIsNull(UUID aggregateId);
}
