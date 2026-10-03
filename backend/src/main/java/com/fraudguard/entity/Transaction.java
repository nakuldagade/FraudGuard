package com.fraudguard.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import com.fraudguard.dto.RuleHit;
import java.util.List;

@Entity                          // this class maps to a database table
@Table(name = "transactions")    // ...the table named "transactions"
@Getter                          // Lombok generates getX() for every field
@Setter                          // Lombok generates setX() for every field
@NoArgsConstructor               // Lombok generates the empty constructor JPA requires
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)   // Hibernate generates the UUID for us
    private UUID id;

    // The final decision (Phase 7): the policy that produced it, why, and everything it was based on
    @Column(name = "policy_version", length = 32)
    private String policyVersion;

    @Column(name = "reason_codes", length = 500)
    private String reasonCodes;                // comma-separated, e.g. HIGH_VELOCITY,ML_ANOMALY_VERY_HIGH

    @Column(name = "decision_explanation", length = 1000)
    private String decisionExplanation;

    @Column(name = "decision_inputs", length = 8000)
    private String decisionInputs;             // JSON snapshot of the DecisionInputs record

    // ID of the last Kafka event applied to this row (see V2 migration); used to skip duplicates
    @Column(name = "processed_event_id")
    private UUID processedEventId;

    // updatable = false: written on insert and never changed afterwards
    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Column(name = "account_id", nullable = false, updatable = false, length = 64)
    private String accountId;

    @Column(name = "merchant_id", updatable = false, length = 64)       // optional
    private String merchantId;

    // Stored as text ("DEPOSIT"), not a number, so the table stays readable
    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, updatable = false, length = 16)
    private TransactionType transactionType;

    // BigDecimal (never double) for money: no floating-point rounding errors
    @Column(nullable = false, updatable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "device_id", updatable = false, length = 64)         // optional
    private String deviceId;

    @Column(updatable = false, length = 2)                              // optional
    private String country;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    // The only business field that changes later (PENDING -> APPROVED/REVIEW/BLOCKED)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TransactionStatus status;

    // Result of the rule engine (Phase 5): total risk 0-100 and the rules that fired
    @Column(name = "risk_score")
    private Integer riskScore;

    @Convert(converter = RuleHitsConverter.class)
    @Column(name = "rule_hits", length = 4000)
    private List<RuleHit> ruleHits;

    // ML anomaly score (Phase 6) and the model that produced it. Both are null when the ML
    // service was unavailable for this transaction.
    @Column(name = "anomaly_score")
    private Double anomalyScore;

    @Column(name = "model_version", length = 64)
    private String modelVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist   // runs automatically just before the first INSERT
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate    // runs automatically before every UPDATE
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}