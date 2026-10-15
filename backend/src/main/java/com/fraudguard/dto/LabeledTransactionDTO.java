package com.fraudguard.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fraudguard.entity.ReviewAction;
import com.fraudguard.entity.TransactionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// One training example: the analyst's verdict (label) plus the features the system saw when it decided.
public record LabeledTransactionDTO(
        UUID transactionId,
        int label,                      // 1 = analyst confirmed fraud, 0 = analyst marked legitimate
        ReviewAction action,            // the analyst action the label came from
        String reviewer,
        Instant labeledAt,              // when the analyst decided
        Instant occurredAt,             // when the payment happened (used for time-ordered splits)
        BigDecimal amount,
        TransactionStatus status,       // what the system decided at the time
        Integer riskScore,
        Double anomalyScore,            // ML score at the time (null if the ML service was down)
        String modelVersion,
        JsonNode decisionInputs         // stored snapshot of the inputs at decision time (no leakage)
) {
}