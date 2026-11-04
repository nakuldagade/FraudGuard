package com.fraudguard.dto;

import java.time.Instant;

// One hour of activity for the dashboard chart: how many transactions were decided each way in that hour.
public record TrendPointDTO(Instant hour, long approved, long review, long blocked, long pending) {
}
