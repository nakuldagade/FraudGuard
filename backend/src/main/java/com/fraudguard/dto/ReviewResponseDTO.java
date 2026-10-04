package com.fraudguard.dto;

import com.fraudguard.entity.AnalystReview;
import com.fraudguard.entity.ReviewAction;
import java.time.Instant;
import java.util.UUID;

public record ReviewResponseDTO(
        UUID id,
        ReviewAction action,
        String note,
        String reviewer,
        Instant createdAt
) {
    public static ReviewResponseDTO from(AnalystReview r) {
        return new ReviewResponseDTO(r.getId(), r.getAction(), r.getNote(), r.getReviewer(), r.getCreatedAt());
    }
}