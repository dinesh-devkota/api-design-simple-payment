package com.customercare.service;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Immutable value object holding the computed results of a one-time payment.
 *
 * <p>Created by {@link impl.PaymentServiceImpl} and consumed by
 * {@link com.customercare.mapper.PaymentMapper} to assemble the HTTP response,
 * keeping the service free of manual response construction.
 *
 * @param matchPercentage tier-based match percentage applied (1, 3, or 5)
 * @param matchAmount     dollar amount of the match deducted
 * @param newBalance      account balance after payment + match deduction
 * @param nextDueDate     weekend-adjusted next payment due date
 */
public record PaymentCalculationResult(
        int        matchPercentage,
        BigDecimal matchAmount,
        BigDecimal newBalance,
        LocalDate  nextDueDate) {
}

