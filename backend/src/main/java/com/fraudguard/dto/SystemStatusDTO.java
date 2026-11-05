package com.fraudguard.dto;

import java.time.Instant;

// A snapshot for the admin Operations tab. A count is null when it could not be read (for example the database is down).
public record SystemStatusDTO(
        String database,            // UP or DOWN
        String redis,               // UP or DOWN
        String mlCircuit,           // CLOSED (ML in use), OPEN (rules only for a while) or DISABLED
        Long outboxPending,         // events saved but not yet published to Kafka
        Long stuckPending,          // transactions PENDING for longer than stuckAfterSeconds
        long stuckAfterSeconds,
        Instant checkedAt
) {
}
