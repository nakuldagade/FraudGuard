package com.fraudguard.repository;

import com.fraudguard.dto.TransactionSearchCriteria;
import com.fraudguard.entity.Transaction;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

// Builds the database query for the dashboard list from the optional filters.
// Only the filters that were provided become part of the query ("WHERE a AND b AND ...").
public final class TransactionSpecifications {

    private TransactionSpecifications() {
    }

    public static Specification<Transaction> matching(TransactionSearchCriteria c) {
        return (root, query, cb) -> {
            List<Predicate> conditions = new ArrayList<>();

            if (c.statuses() != null && !c.statuses().isEmpty()) {
                conditions.add(root.get("status").in(c.statuses()));
            }
            if (c.reviewState() != null) {
                conditions.add(cb.equal(root.get("reviewState"), c.reviewState()));
            }
            // Rows not processed yet have no risk score, so a score filter leaves them out
            if (c.minRiskScore() != null) {
                conditions.add(cb.greaterThanOrEqualTo(root.<Integer>get("riskScore"), c.minRiskScore()));
            }
            if (c.maxRiskScore() != null) {
                conditions.add(cb.lessThanOrEqualTo(root.<Integer>get("riskScore"), c.maxRiskScore()));
            }
            if (c.from() != null) {
                conditions.add(cb.greaterThanOrEqualTo(root.<Instant>get("occurredAt"), c.from()));
            }
            if (c.to() != null) {
                conditions.add(cb.lessThan(root.<Instant>get("occurredAt"), c.to()));   // end is exclusive
            }
            if (c.accountId() != null && !c.accountId().isBlank()) {
                conditions.add(cb.equal(root.get("accountId"), c.accountId().trim()));
            }
            return cb.and(conditions.toArray(new Predicate[0]));
        };
    }
}