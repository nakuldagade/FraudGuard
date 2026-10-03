package com.fraudguard.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// The JSON body sent to the ML service (POST /score).
// The names after @JsonProperty must match ml-service/app/schemas.py exactly.
public record MlScoreRequest(
        @JsonProperty("transaction_id") String transactionId,
        @JsonProperty("amount") double amount,
        @JsonProperty("hour_of_day") int hourOfDay,                       // UTC hour 0-23
        @JsonProperty("transaction_count_5m") int transactionCount5m,
        @JsonProperty("transaction_count_1h") int transactionCount1h,
        @JsonProperty("history_count") int historyCount,
        @JsonProperty("average_amount") double averageAmount,
        @JsonProperty("amount_z_score") Double amountZScore,              // null = not enough history
        @JsonProperty("seconds_since_previous") Long secondsSincePrevious,   // null = first transaction
        @JsonProperty("new_device") boolean newDevice,
        @JsonProperty("new_merchant") boolean newMerchant,
        @JsonProperty("country_changed") boolean countryChanged
) {
}