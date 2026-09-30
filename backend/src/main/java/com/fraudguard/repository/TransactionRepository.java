package com.fraudguard.repository;

import com.fraudguard.entity.Transaction;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

// JpaRepository already provides save(), findById(), findAll(), etc.
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    // Spring generates the SQL from the method name:
    //   SELECT * FROM transactions WHERE idempotency_key = ?
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);
}