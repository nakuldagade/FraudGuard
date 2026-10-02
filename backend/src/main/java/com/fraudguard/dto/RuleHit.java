package com.fraudguard.dto;

// One rule that fired for a transaction: which rule, how much it adds to the risk score,
// and a human-readable reason that includes the actual numbers.
// Stored with the transaction and returned by the API, so every decision can be explained.
public record RuleHit(String code, int score, String reason) {
}