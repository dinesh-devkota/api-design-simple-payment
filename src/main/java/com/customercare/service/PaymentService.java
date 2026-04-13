package com.customercare.service;

import com.customercare.dto.OneTimePaymentRequest;
import com.customercare.dto.OneTimePaymentResponse;

/**
 * Orchestrates the full one-time payment flow:
 * balance deduction, match calculation, due-date calculation, and persistence.
 *
 * <p>Request/response types are generated from {@code openapi.yaml}.
 */
public interface PaymentService {

    /**
     * Processes a one-time payment for the given request.
     *
     * @param request validated payment request
     * @return response containing updated balance and next due date
     */
    OneTimePaymentResponse processOneTimePayment(OneTimePaymentRequest request);
}
