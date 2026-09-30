package com.fraudguard.service;

import com.fraudguard.dto.TransactionRequestDTO;
import com.fraudguard.dto.TransactionResponseDTO;
import java.util.UUID;

public interface TransactionService {

    // Result of create(): the transaction plus a flag saying whether it was newly created (true)
    // or an idempotent replay of an earlier identical request (false).
    record CreateResult(TransactionResponseDTO transaction, boolean created) {
    }

    // Stores a new PENDING transaction, or returns the existing one if the same
    // Idempotency-Key + same body was already processed.
    CreateResult create(String idempotencyKey, TransactionRequestDTO request);

    // Loads one transaction by ID; throws ResourceNotFoundException if it doesn't exist.
    TransactionResponseDTO getById(UUID id);
}