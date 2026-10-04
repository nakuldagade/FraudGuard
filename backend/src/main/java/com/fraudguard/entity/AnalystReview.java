package com.fraudguard.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// One analyst action on one transaction. Append-only: rows are never updated, so the history of
// who decided what, and when, is always complete.
@Entity
@Table(name = "analyst_reviews")
@Getter
@Setter
@NoArgsConstructor
public class AnalystReview {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 32)
    private ReviewAction action;

    @Column(updatable = false, length = 500)
    private String note;

    @Column(nullable = false, updatable = false, length = 64)
    private String reviewer;      // typed by the analyst for now; real identity arrives with login (Phase 10)

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}