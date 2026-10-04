package com.fraudguard.dto;

import com.fraudguard.entity.ReviewState;
import com.fraudguard.entity.TransactionStatus;
import java.time.Instant;
import java.util.List;

// The optional filters of the transaction list. A null field means "don't filter on this".
public record TransactionSearchCriteria(
        List<TransactionStatus> statuses,   // the model's decision, any of these
        ReviewState reviewState,            // the analyst's verdict
        Integer minRiskScore,               // rule score range, inclusive (0-100)
        Integer maxRiskScore,
        Instant from,                       // transaction time: from (inclusive)...
        Instant to,                         // ...to (exclusive)
        String accountId
) {
}