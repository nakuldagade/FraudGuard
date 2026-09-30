package com.fraudguard.entity;

// Lifecycle of a transaction.
// New transactions start as PENDING. The decision engine (Phase 7) sets one of the others:
//   APPROVED = let it through, REVIEW = an analyst should look, BLOCKED = rejected.
public enum TransactionStatus {
    PENDING,
    APPROVED,
    REVIEW,
    BLOCKED
}