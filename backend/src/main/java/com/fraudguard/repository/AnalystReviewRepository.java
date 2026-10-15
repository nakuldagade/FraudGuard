package com.fraudguard.repository;

import com.fraudguard.entity.AnalystReview;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AnalystReviewRepository extends JpaRepository<AnalystReview, UUID> {

    // All actions on one transaction, newest first
    List<AnalystReview> findByTransactionIdOrderByCreatedAtDesc(UUID transactionId);

    // The most recent action for every reviewed transaction (Phase 9: the analyst's current verdict
    // is what becomes the training label). The sub-query picks the newest created_at per transaction.
    @Query("""
            select r from AnalystReview r
            where r.createdAt = (select max(r2.createdAt) from AnalystReview r2
                                 where r2.transactionId = r.transactionId)
            """)
    List<AnalystReview> findLatestPerTransaction();
}