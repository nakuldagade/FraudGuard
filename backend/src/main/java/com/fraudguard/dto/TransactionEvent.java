package com.fraudguard.dto;

import com.fraudguard.entity.Transaction;
import com.fraudguard.entity.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// The message published to Kafka (as JSON). The envelope fields describe the event itself;
// the payload describes the transaction.
public record TransactionEvent(
        UUID eventId,          // unique per event; consumers use it to detect duplicate deliveries
        String eventType,      // what happened, e.g. TRANSACTION_CREATED
        int schemaVersion,     // format version: lets us change the payload later without breaking consumers
        Instant createdAt,     // when the event was produced
        Payload payload
) {
    public static final String TYPE_CREATED = "TRANSACTION_CREATED";
    public static final int SCHEMA_VERSION = 1;

    // Everything a downstream consumer needs to know about the transaction
    public record Payload(
            UUID transactionId,
            String accountId,
            String merchantId,
            TransactionType transactionType,
            BigDecimal amount,
            String currency,
            String deviceId,
            String country,
            Instant occurredAt
    ) {
    }

    // Builds a "transaction created" event from a saved transaction
    public static TransactionEvent created(Transaction t) {
        return new TransactionEvent(
                UUID.randomUUID(), TYPE_CREATED, SCHEMA_VERSION, Instant.now(),
                new Payload(t.getId(), t.getAccountId(), t.getMerchantId(), t.getTransactionType(),
                        t.getAmount(), t.getCurrency(), t.getDeviceId(), t.getCountry(),
                        t.getOccurredAt()));
    }
}