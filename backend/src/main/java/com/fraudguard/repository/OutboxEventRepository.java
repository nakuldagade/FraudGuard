package com.fraudguard.repository;

import com.fraudguard.entity.OutboxEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    // Spring builds the query from the name: unsent rows (published_at IS NULL),
    // oldest first, at most 50 per round.
    List<OutboxEvent> findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();

    // Used by tests to find the outbox rows belonging to one transaction
    List<OutboxEvent> findByAggregateId(UUID aggregateId);
}