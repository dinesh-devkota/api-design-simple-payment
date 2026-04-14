package com.customercare.domain.exception;

/**
 * Thrown when the payment amount plus match exceeds the account balance.
 * Maps to HTTP 422 via the global exception handler in the app module.
 */
public class InsufficientBalanceException extends RuntimeException {

    public InsufficientBalanceException(String message) {
        super(message);
    }
}
