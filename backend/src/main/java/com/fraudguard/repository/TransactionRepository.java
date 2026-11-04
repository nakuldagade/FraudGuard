package com.fraudguard.repository;

import com.fraudguard.entity.ReviewState;
import com.fraudguard.entity.Transaction;
import com.fraudguard.entity.TransactionStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

// JpaRepository provides save(), findById(), etc.
// JpaSpecificationExecutor adds findAll(specification, pageable) for the dashboard's dynamic filters.
public interface TransactionRepository extends JpaRepository<Transaction, UUID>, JpaSpecificationExecutor<Transaction> {

    // Spring generates the SQL from the method name:
    //   SELECT * FROM transactions WHERE idempotency_key = ?
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    // Counts for the dashboard summary
    long countByStatus(TransactionStatus status);

    long countByReviewState(ReviewState reviewState);

    long countByStatusInAndReviewState(Collection<TransactionStatus> statuses, ReviewState reviewState);

    // Phase 9: how many transactions each model version scored, and when it last scored one.
    // Each row is [modelVersion, count, lastCreatedAt]. Transactions without a score are excluded here.
    @Query("""
            select t.modelVersion, count(t), max(t.createdAt)
            from Transaction t
            where t.modelVersion is not null
            group by t.modelVersion
            """)
    List<Object[]> summarizeModelUsage();

    // Transactions decided without an ML score (the ML service was unavailable)
    long countByModelVersionIsNull();

    // Phase 10: PENDING transactions older than a cutoff. The pipeline normally decides within a
    // second or two, so an old PENDING row means its event was lost (e.g. dead-lettered).
    long countByStatusAndCreatedAtBefore(TransactionStatus status, Instant cutoff);

    // The oldest stuck rows first, capped by the Pageable, for the re-drive endpoint
    List<Transaction> findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
            TransactionStatus status, Instant cutoff, Pageable pageable);
}
