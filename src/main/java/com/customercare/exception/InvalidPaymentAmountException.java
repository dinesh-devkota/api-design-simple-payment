package com.customercare.exception;

/**
 * Thrown when a payment amount is zero or negative.
 * Maps to HTTP 400.
 */
public class InvalidPaymentAmountException extends RuntimeException {

    public InvalidPaymentAmountException(String message) {
        super(message);
    }
}

