package com.fraudguard.decision;

import com.fraudguard.dto.RuleHit;
import com.fraudguard.dto.VelocityFeatures;
import com.fraudguard.entity.TransactionStatus;
import java.time.Instant;
import java.util.List;

// Everything the decision was based on, saved as JSON with the transaction (decision_inputs column).
// With this, any decision can be re-examined later, even after the rules or thresholds change.
public record DecisionInputs(
        String policyVersion,
        Instant decidedAt,
        VelocityFeatures velocityFeatures,   // the Redis features
        int ruleScore,                       // rule engine score, 0-100
        TransactionStatus ruleStatus,        // what the rules alone would have decided
        List<RuleHit> ruleHits,
        Double anomalyScore,                 // ML score; null when unavailable. A ranking score, NOT a fraud probability
        String modelVersion,                 // which model produced it; null when unavailable
        MlSignal mlSignal,                   // how the policy treated it
        Thresholds thresholds                // the numbers in force at the time
) {
    public record Thresholds(int ruleReviewAt, int ruleBlockAt, int mlMinHistory,
                             double mlReviewAt, double mlVeryHighAt) {
    }
}