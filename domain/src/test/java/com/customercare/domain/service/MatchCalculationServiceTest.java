package com.customercare.domain.service;

import com.customercare.domain.service.impl.MatchCalculationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link MatchCalculationServiceImpl} — no Spring context.
 * Covers every tier boundary explicitly.
 */
class MatchCalculationServiceTest {

    private MatchCalculationService service;

    @BeforeEach
    void setUp() {
        service = new MatchCalculationServiceImpl();
    }

    @ParameterizedTest(name = "amount={0} → pct={1}")
    @CsvSource({
            // Low tier: 0 < x < 10
            "0.01,  1",   // minimum valid payment
            "9.99,  1",   // low-tier max
            // Mid tier: 10 <= x < 50
            "10.00, 3",   // mid-tier min (spec boundary)
            "49.99, 3",   // mid-tier max
            // High tier: x >= 50
            "50.00, 5",   // high-tier min (spec boundary)
            "75.00, 5",   // spec example ($75 payment)
            "100.00, 5",
            "999.99, 5"
    })
    @DisplayName("getMatchPercentage — tier boundary table")
    void getMatchPercentage(String amount, int expectedPct) {
        assertThat(service.getMatchPercentage(new BigDecimal(amount)))
                .isEqualTo(expectedPct);
    }

    @ParameterizedTest(name = "amount={0} → matchAmount={1}")
    @CsvSource({
            // Low tier 1%: note $0.01 * 1% = $0.0001 → rounds to $0.00 (HALF_UP, sub-cent)
            "0.01,  0.00",
            "9.99,  0.10",  // 9.99 * 1% = 0.0999 → 0.10 (HALF_UP)
            // Mid tier 3%
            "10.00, 0.30",  // spec example: $10 payment → $0.30 match
            "49.99, 1.50",  // 49.99 * 3% = 1.4997 → 1.50 (HALF_UP)
            // High tier 5%
            "50.00, 2.50",
            "75.00, 3.75",  // spec example: $75 payment → $3.75 match
            "100.00, 5.00"
    })
    @DisplayName("calculateMatchAmount — tier boundary table")
    void calculateMatchAmount(String amount, String expectedMatch) {
        assertThat(service.calculateMatchAmount(new BigDecimal(amount)))
                .isEqualByComparingTo(new BigDecimal(expectedMatch));
    }
}

