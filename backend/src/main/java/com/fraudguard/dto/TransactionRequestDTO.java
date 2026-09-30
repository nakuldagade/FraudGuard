package com.fraudguard.dto;

import com.fraudguard.entity.TransactionType;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;

// A Java "record": an immutable data holder that Jackson fills from the JSON body.
public record TransactionRequestDTO(

        @NotBlank @Size(max = 64) String accountId,     // required, not empty, max 64 chars

        @Size(max = 64) String merchantId,              // optional

        @NotNull TransactionType transactionType,       // required; must be one of the enum values

        @NotNull                                        // required
        @DecimalMin(value = "0.01", message = "must be at least 0.01")
        @Digits(integer = 12, fraction = 2, message = "max 12 integer digits and 2 decimal places")
        BigDecimal amount,

        // Exactly 3 uppercase letters, e.g. INR, USD
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter uppercase ISO code, e.g. INR")
        String currency,

        @Size(max = 64) String deviceId,                // optional

        // Optional; if present, exactly 2 uppercase letters, e.g. IN
        @Pattern(regexp = "^[A-Z]{2}$", message = "must be a 2-letter uppercase country code, e.g. IN")
        String country,

        Instant occurredAt                              // optional; defaults to "now" in the service
) {
}