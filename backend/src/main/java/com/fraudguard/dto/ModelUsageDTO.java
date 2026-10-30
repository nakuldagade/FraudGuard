package com.fraudguard.dto;

import java.time.Instant;
import java.util.List;

// Which ML models scored the stored transactions: the audit trail of Phase 9 promotions.
public record ModelUsageDTO(
        List<Entry> models,        // newest-used first
        long unscored              // decided by rules only, because the ML service was unavailable
) {
    public record Entry(
            String modelVersion,   // e.g. isoforest-v1, gbm-20261030-052117
            long transactions,     // how many transactions this version scored
            Instant lastScoredAt   // when it last scored one
    ) {
    }
}
