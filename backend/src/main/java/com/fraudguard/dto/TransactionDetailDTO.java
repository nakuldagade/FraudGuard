package com.fraudguard.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

// Everything the detail screen needs in one call.
public record TransactionDetailDTO(
        TransactionResponseDTO transaction,    // the transaction with its decision, reasons and explanation
        JsonNode decisionInputs,               // the stored snapshot: velocity features, rule hits, ML score, thresholds
        List<ReviewResponseDTO> reviews        // analyst actions, newest first
) {
}