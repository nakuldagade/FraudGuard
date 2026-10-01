package com.fraudguard.dto;

import java.math.BigDecimal;

// How active an account has been recently, as seen at the moment of ONE transaction.
// Phases 5-7 (rules, ML score, decision) read these values.
public record VelocityFeatures(
        int transactionCount5m,        // transactions in the last 5 minutes, INCLUDING this one
        int transactionCount1h,        // ...in the last hour, including this one
        BigDecimal amountSum5m,        // total amount in those windows, including this one
        BigDecimal amountSum1h,
        int historyCount,              // earlier transactions the baseline below is built from
        BigDecimal averageAmount,      // the account's usual amount BEFORE this transaction
        BigDecimal amountStdDev,       // how much its amounts normally vary
        Double amountZScore,           // standard deviations away from usual; null if history is too short
        boolean newDevice,             // device never seen for this account (false if no device was sent)
        boolean newMerchant,           // merchant never seen for this account (false if none was sent)
        Long secondsSincePreviousTransaction,   // null for the first transaction (or out-of-order events)
        boolean countryChanged         // differs from the previous transaction's country
) {
}