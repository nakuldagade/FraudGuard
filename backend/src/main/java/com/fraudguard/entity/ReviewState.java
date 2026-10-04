package com.fraudguard.entity;

// The CURRENT analyst verdict on a transaction (kept on the transaction row for fast filtering).
// This is separate from the model's decision (TransactionStatus), which analysts never change.
public enum ReviewState {
    UNREVIEWED,           // no analyst has looked at it yet
    CONFIRMED_FRAUD,
    MARKED_LEGITIMATE,
    NEEDS_MORE_REVIEW
}