package com.fraudguard.dto;

import java.util.List;
import org.springframework.data.domain.Page;

// One page of results. (Our own shape is more stable than serialising Spring's Page directly.)
public record PageResponseDTO<T>(
        List<T> content,
        int page,              // 0-based
        int size,
        long totalElements,
        int totalPages
) {
    public static <T> PageResponseDTO<T> from(Page<T> page) {
        return new PageResponseDTO<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}