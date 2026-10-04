package com.fraudguard.service;

import com.fraudguard.dto.PageResponseDTO;
import com.fraudguard.dto.ReviewResponseDTO;
import com.fraudguard.dto.TransactionDetailDTO;
import com.fraudguard.dto.TransactionRequestDTO;
import com.fraudguard.dto.TransactionResponseDTO;
import com.fraudguard.dto.TransactionSearchCriteria;
import com.fraudguard.dto.TransactionStatusUpdateDTO;
import com.fraudguard.dto.TransactionSummaryDTO;
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

    // Dashboard list: filtered, newest first, one page at a time
    PageResponseDTO<TransactionResponseDTO> search(TransactionSearchCriteria criteria, int page, int size);

    // Dashboard header numbers
    TransactionSummaryDTO summary();

    // Dashboard detail screen: the transaction, its stored decision inputs, and the review history
    TransactionDetailDTO getDetail(UUID id);

    // Records an analyst action and updates the transaction's review state
    ReviewResponseDTO review(UUID id, TransactionStatusUpdateDTO request);
}