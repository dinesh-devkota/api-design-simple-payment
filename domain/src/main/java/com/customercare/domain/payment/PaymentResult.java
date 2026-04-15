package com.customercare.domain.payment;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Immutable value object returned by {@link ProcessPaymentUseCase}.
 *
 * <p>Contains every field needed to build the HTTP response without leaking
 * any contract DTO into the domain layer.
 *
 * @param userId               the customer's user identifier
 * @param previousBalance      account balance <em>before</em> the payment
 * @param paymentAmount        the amount paid
 * @param matchPercentage      tier-based match percentage (1, 3, or 5)
 * @param matchAmount          dollar amount matched
 * @param newBalance           account balance after payment + match deduction
 * @param nextPaymentDueDate   weekend-adjusted next payment due date
 * @param paymentDate          server-recorded date the payment was processed
 */
public record PaymentResult(
        String     userId,
        BigDecimal previousBalance,
        BigDecimal paymentAmount,
        int        matchPercentage,
        BigDecimal matchAmount,
        BigDecimal newBalance,
        LocalDate  nextPaymentDueDate,
        LocalDate  paymentDate) {
}

