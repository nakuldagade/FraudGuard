package com.fraudguard.exception;

// Thrown when something is looked up by ID and doesn't exist.
// GlobalExceptionHandler turns it into an HTTP 404 response.
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}