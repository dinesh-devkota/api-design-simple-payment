package com.customercare.service;

import java.time.LocalDate;

/**
 * Calculates the next payment due date as payment date + 15 days,
 * adjusting Saturday → Monday and Sunday → Monday.
 */
public interface DueDateCalculationService {

    /**
     * Returns the weekend-adjusted due date.
     *
     * @param paymentDate the date on which the payment was made
     * @return due date (never a Saturday or Sunday)
     */
    LocalDate calculateDueDate(LocalDate paymentDate);
}

