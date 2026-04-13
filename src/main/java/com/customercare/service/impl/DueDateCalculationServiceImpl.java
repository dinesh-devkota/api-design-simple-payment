package com.customercare.service.impl;

import com.customercare.service.DueDateCalculationService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * Calculates payment due date as payment date + 15 days,
 * then shifts Saturday → Monday (+2) and Sunday → Monday (+1).
 */
@Service
public class DueDateCalculationServiceImpl implements DueDateCalculationService {

    @Override
    public LocalDate calculateDueDate(LocalDate paymentDate) {
        LocalDate rawDueDate = paymentDate.plusDays(15);

        return switch (rawDueDate.getDayOfWeek()) {
            case SATURDAY -> rawDueDate.plusDays(2);
            case SUNDAY   -> rawDueDate.plusDays(1);
            default       -> rawDueDate;
        };
    }
}

