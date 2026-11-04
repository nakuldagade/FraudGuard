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

    // Dashboard chart: transactions per hour and status since a point in time. Each row is [hour, status, count].
    // Native SQL. The hour is computed from epoch seconds, so it is a UTC hour whatever the database session's
    // time zone is (date_trunc would cut at the session's local hour, e.g. :30 past in India). Uses created_at.
    @Query(value = """
            select cast(floor(extract(epoch from created_at) / 3600) * 3600 as bigint) as hour_epoch, status, count(*) as n
            from transactions where created_at >= :since
            group by 1, 2 order by 1
            """, nativeQuery = true)
    List<Object[]> hourlyStatusCounts(@org.springframework.data.repository.query.Param("since") Instant since);

    // Phase 11: the whole summary from ONE query (it used to run about 11 separate counts).
    // Each row is [status, reviewState, count].
    @Query("select t.status, t.reviewState, count(t) from Transaction t group by t.status, t.reviewState")
    List<Object[]> countByStatusAndReviewState();

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
