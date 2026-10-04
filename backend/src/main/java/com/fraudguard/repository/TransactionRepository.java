package com.fraudguard.repository;

import com.fraudguard.entity.ReviewState;
import com.fraudguard.entity.Transaction;
import com.fraudguard.entity.TransactionStatus;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

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
}