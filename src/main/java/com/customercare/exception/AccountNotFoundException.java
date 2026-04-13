package com.customercare.exception;

/**
 * Thrown when a requested account does not exist in Redis.
 * Maps to HTTP 404.
 */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(String message) {
        super(message);
    }
}

