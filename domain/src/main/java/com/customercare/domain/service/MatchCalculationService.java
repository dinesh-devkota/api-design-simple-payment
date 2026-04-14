package com.customercare.domain.service;

import java.math.BigDecimal;

/**
 * Calculates the match percentage and match amount for a given payment amount.
 *
 * <p>Tier table:
 * <ul>
 *   <li>{@code 0 < amount < 10}   → 1 %</li>
 *   <li>{@code 10 <= amount < 50} → 3 %</li>
 *   <li>{@code amount >= 50}      → 5 %</li>
 * </ul>
 */
public interface MatchCalculationService {

    /**
     * Returns the applicable match percentage (1, 3, or 5) for the given payment amount.
     *
     * @param paymentAmount the payment amount; must be {@code > 0}
     * @return match percentage as an integer
     */
    int getMatchPercentage(BigDecimal paymentAmount);

    /**
     * Calculates the match dollar amount (rounded to 2 decimal places, HALF_UP).
     *
     * @param paymentAmount the payment amount; must be {@code > 0}
     * @return match amount
     */
    BigDecimal calculateMatchAmount(BigDecimal paymentAmount);
}

