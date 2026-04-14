package com.customercare.domain.exception;

/**
 * Thrown when a payment amount is zero or negative.
 * Maps to HTTP 400 via the global exception handler in the app module.
 */
public class InvalidPaymentAmountException extends RuntimeException {

    public InvalidPaymentAmountException(String message) {
        super(message);
    }
}

