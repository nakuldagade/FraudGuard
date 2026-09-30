package com.fraudguard.exception;

// Thrown when an Idempotency-Key is reused with a DIFFERENT request body.
// GlobalExceptionHandler turns it into an HTTP 409 (Conflict) response.

public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String message) {
        super(message);
    }
}