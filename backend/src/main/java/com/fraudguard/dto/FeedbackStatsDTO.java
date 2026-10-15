package com.fraudguard.dto;

// How much feedback exists, for the dashboard and for deciding when retraining is worthwhile.
public record FeedbackStatsDTO(
        long confirmedFraud,            // latest action = CONFIRM_FRAUD     -> label 1
        long markedLegitimate,          // latest action = MARK_LEGITIMATE   -> label 0
        long furtherReviewRequested,    // latest action = REQUEST_FURTHER_REVIEW -> no label yet
        long unreviewed,                // no analyst action at all -> no label
        long usableLabels               // confirmedFraud + markedLegitimate
) {
}