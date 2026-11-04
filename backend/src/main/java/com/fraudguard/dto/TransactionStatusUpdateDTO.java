package com.fraudguard.dto;

import com.fraudguard.entity.ReviewAction;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// What the dashboard sends when an analyst acts on a transaction.
public record TransactionStatusUpdateDTO(
        @NotNull ReviewAction action,                 // CONFIRM_FRAUD / MARK_LEGITIMATE / REQUEST_FURTHER_REVIEW
        @Size(max = 500) String note,                 // optional comment
        @Size(max = 64) String reviewer               // optional and IGNORED: the reviewer is the logged-in user (kept so old clients still work)
) {
}