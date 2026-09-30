package com.fraudguard.controller;

import com.fraudguard.dto.TransactionRequestDTO;
import com.fraudguard.dto.TransactionResponseDTO;
import com.fraudguard.service.TransactionService;
import jakarta.validation.Valid;
import java.net.URI;
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

    // GET /api/transactions/{id}
    @GetMapping("/{id}")
    public TransactionResponseDTO getById(@PathVariable UUID id) {
        return service.getById(id);
    }
}