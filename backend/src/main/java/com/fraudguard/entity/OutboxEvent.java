package com.fraudguard.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// One row = one Kafka message that must be delivered (the "outbox" of the application).
@Entity
@Table(name = "outbox_events")
@Getter
@Setter
@NoArgsConstructor
public class OutboxEvent {

    @Id
    private UUID id;                    // we set this ourselves: it equals the event's eventId

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;           // which transaction the event belongs to

    @Column(nullable = false, length = 128)
    private String topic;               // where to publish it

    @Column(name = "message_key", nullable = false, length = 128)
    private String messageKey;          // Kafka key: same account => same partition => ordered

    @Column(nullable = false, length = 4000)
    private String payload;             // the JSON event

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;        // null until the relay has delivered it to Kafka
}