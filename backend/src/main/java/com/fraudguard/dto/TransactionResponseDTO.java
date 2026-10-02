package com.fraudguard.dto;

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
        TransactionStatus status,
        Integer riskScore,        // NEW: null until the transaction has been processed
        List<RuleHit> ruleHits,   // NEW: the rules that fired, with reasons (empty if none)
        Instant createdAt
) {
    // Converts a database entity into the public response shape
    public static TransactionResponseDTO from(Transaction t) {
        return new TransactionResponseDTO(
                t.getId(), t.getAccountId(), t.getMerchantId(), t.getTransactionType(),
                t.getAmount(), t.getCurrency(), t.getDeviceId(), t.getCountry(),
                t.getOccurredAt(), t.getStatus(), t.getRiskScore(),
                t.getRuleHits() == null ? List.of() : t.getRuleHits(),
                t.getCreatedAt());
    }
}