package com.fraudguard.dto;

import java.util.Map;

// Numbers for the dashboard header.
public record TransactionSummaryDTO(
        long total,
        long awaitingReview,                  // decision REVIEW or BLOCKED, and no analyst has looked yet
        Map<String, Long> byStatus,           // count per model decision
        Map<String, Long> byReviewState       // count per analyst verdict
) {
}