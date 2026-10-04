package com.fraudguard.dto;

import com.fraudguard.entity.ReviewState;
import com.fraudguard.entity.Transaction;
import com.fraudguard.entity.TransactionStatus;
import com.fraudguard.entity.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransactionResponseDTO(
        UUID id,
        String accountId,
        String merchantId,
        TransactionType transactionType,
        BigDecimal amount,
        String currency,
        String deviceId,
        String country,
        Instant occurredAt,
        TransactionStatus status,       // the model's final decision (never changed by analysts)
        Integer riskScore,              // the RULE engine's score, 0-100 (null until processed)
        List<RuleHit> ruleHits,         // the rules that fired, with reasons (empty if none)
        Double anomalyScore,            // ML anomaly score; null if the ML service was unavailable
        String modelVersion,            // which model produced it, e.g. isoforest-v1
        String policyVersion,           // which decision policy produced the status
        List<String> reasonCodes,       // why, e.g. [HIGH_VELOCITY, ML_ANOMALY_VERY_HIGH]
        String decisionExplanation,     // the same in plain English
        ReviewState reviewState,        // NEW: the analyst's current verdict (UNREVIEWED until someone acts)
        Instant createdAt
) {
    // Converts a database entity into the public response shape
    public static TransactionResponseDTO from(Transaction t) {
        return new TransactionResponseDTO(
                t.getId(), t.getAccountId(), t.getMerchantId(), t.getTransactionType(),
                t.getAmount(), t.getCurrency(), t.getDeviceId(), t.getCountry(),
                t.getOccurredAt(), t.getStatus(), t.getRiskScore(),
                t.getRuleHits() == null ? List.of() : t.getRuleHits(),
                t.getAnomalyScore(), t.getModelVersion(),
                t.getPolicyVersion(),
                t.getReasonCodes() == null || t.getReasonCodes().isBlank()
                        ? List.of() : List.of(t.getReasonCodes().split(",")),
                t.getDecisionExplanation(),
                t.getReviewState(),
                t.getCreatedAt());
    }
}