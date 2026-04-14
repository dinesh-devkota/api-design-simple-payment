package com.customercare.domain.service.impl;

import com.customercare.domain.service.MatchCalculationService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Tier-based match calculation.
 *
 * <p>All comparisons use {@link BigDecimal#compareTo} to avoid {@code equals()} scale issues.
 */
@Service
public class MatchCalculationServiceImpl implements MatchCalculationService {

    private static final BigDecimal TEN     = BigDecimal.valueOf(10);
    private static final BigDecimal FIFTY   = BigDecimal.valueOf(50);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Override
    public int getMatchPercentage(BigDecimal paymentAmount) {
        if (paymentAmount.compareTo(TEN) < 0) {
            return 1;
        } else if (paymentAmount.compareTo(FIFTY) < 0) {
            return 3;
        } else {
            return 5;
        }
    }

    @Override
    public BigDecimal calculateMatchAmount(BigDecimal paymentAmount) {
        int pct = getMatchPercentage(paymentAmount);
        return paymentAmount
                .multiply(BigDecimal.valueOf(pct))
                .divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }
}

