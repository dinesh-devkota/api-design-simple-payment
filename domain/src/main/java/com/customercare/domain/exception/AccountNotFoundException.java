package com.customercare.domain.exception;

/**
 * Thrown when a requested account does not exist.
 * Maps to HTTP 404 via the global exception handler in the app module.
 */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(String message) {
        super(message);
    }
}

