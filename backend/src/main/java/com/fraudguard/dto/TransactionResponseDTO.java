package com.fraudguard.dto;

import com.fraudguard.entity.Transaction;
import com.fraudguard.entity.TransactionStatus;
import com.fraudguard.entity.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
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
        Instant createdAt
) {
    // Converts a database entity into the public response shape
    public static TransactionResponseDTO from(Transaction t) {
        return new TransactionResponseDTO(
                t.getId(), t.getAccountId(), t.getMerchantId(), t.getTransactionType(),
                t.getAmount(), t.getCurrency(), t.getDeviceId(), t.getCountry(),
                t.getOccurredAt(), t.getStatus(), t.getCreatedAt());
    }
}