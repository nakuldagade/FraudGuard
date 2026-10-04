package com.fraudguard.controller;

import com.fraudguard.dto.PageResponseDTO;
import com.fraudguard.dto.ReviewResponseDTO;
import com.fraudguard.dto.TransactionDetailDTO;
import com.fraudguard.dto.TransactionRequestDTO;
import com.fraudguard.dto.TransactionResponseDTO;
import com.fraudguard.dto.TransactionSearchCriteria;
import com.fraudguard.dto.TransactionStatusUpdateDTO;
import com.fraudguard.dto.TransactionSummaryDTO;
import com.fraudguard.entity.ReviewState;
import com.fraudguard.entity.TransactionStatus;
import com.fraudguard.service.TransactionService;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController                        // methods return JSON directly
@RequestMapping("/api/transactions")   // base URL for everything in this class
@RequiredArgsConstructor               // constructor injection of the final field below
public class TransactionController {

    private final TransactionService service;

    // POST /api/transactions
    // 202 Accepted = newly stored.  200 OK = identical request replayed (same Idempotency-Key).
    @PostMapping
    public ResponseEntity<TransactionResponseDTO> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,   // required header
            @Valid @RequestBody TransactionRequestDTO request) {       // @Valid runs the DTO's rules

        String key = idempotencyKey.trim();
        if (key.isEmpty() || key.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must be 1-128 characters");
        }

        var result = service.create(key, request);
        TransactionResponseDTO body = result.transaction();
        return ResponseEntity
                .status(result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .location(URI.create("/api/transactions/" + body.id()))   // where to fetch it later
                .body(body);
    }

    // GET /api/transactions?status=REVIEW&status=BLOCKED&reviewState=UNREVIEWED&minRiskScore=40...
    // The dashboard list. Every filter is optional. from/to are ISO instants, e.g. 2026-10-01T00:00:00Z.
    @GetMapping
    public PageResponseDTO<TransactionResponseDTO> search(
            @RequestParam(required = false) List<TransactionStatus> status,
            @RequestParam(required = false) ReviewState reviewState,
            @RequestParam(required = false) Integer minRiskScore,
            @RequestParam(required = false) Integer maxRiskScore,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String accountId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var criteria = new TransactionSearchCriteria(
                status, reviewState, minRiskScore, maxRiskScore, from, to, accountId);
        return service.search(criteria, page, size);
    }

    // GET /api/transactions/summary: numbers for the dashboard header.
    // (The fixed word "summary" wins over the "/{id}" pattern below.)
    @GetMapping("/summary")
    public TransactionSummaryDTO summary() {
        return service.summary();
    }

    // GET /api/transactions/{id}
    @GetMapping("/{id}")
    public TransactionResponseDTO getById(@PathVariable UUID id) {
        return service.getById(id);
    }

    // GET /api/transactions/{id}/detail: transaction + stored decision inputs + review history
    @GetMapping("/{id}/detail")
    public TransactionDetailDTO detail(@PathVariable UUID id) {
        return service.getDetail(id);
    }

    // POST /api/transactions/{id}/review: an analyst confirms fraud, marks legitimate, or asks for more review
    @PostMapping("/{id}/review")
    public ResponseEntity<ReviewResponseDTO> review(
            @PathVariable UUID id,
            @Valid @RequestBody TransactionStatusUpdateDTO request) {
        ReviewResponseDTO saved = service.review(id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }
}