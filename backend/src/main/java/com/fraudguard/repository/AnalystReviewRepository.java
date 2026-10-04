package com.fraudguard.repository;

import com.fraudguard.entity.AnalystReview;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalystReviewRepository extends JpaRepository<AnalystReview, UUID> {

    // All actions on one transaction, newest first
    List<AnalystReview> findByTransactionIdOrderByCreatedAtDesc(UUID transactionId);
}