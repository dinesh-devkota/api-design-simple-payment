package com.customercare.service;

import com.customercare.service.impl.MatchCalculationServiceImpl;
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

    // -------------------------------------------------------------------------
    // Match percentage tier
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "amount={0} → pct={1}")
    @CsvSource({
            "0.01,  1",
            "9.99,  1",
            "10.00, 3",
            "49.99, 3",
            "50.00, 5",
            "100.00, 5",
            "999.99, 5"
    })
    @DisplayName("getMatchPercentage — tier boundary table")
    void getMatchPercentage(String amount, int expectedPct) {
        assertThat(service.getMatchPercentage(new BigDecimal(amount)))
                .isEqualTo(expectedPct);
    }

    // -------------------------------------------------------------------------
    // Match amount calculation
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "amount={0} → matchAmount={1}")
    @CsvSource({
            "0.01,  0.00",   // 0.01 * 1% = 0.0001 → rounds to 0.00
            "9.99,  0.10",   // 9.99 * 1% = 0.0999 → rounds to 0.10
            "10.00, 0.30",   // 10   * 3% = 0.30
            "49.99, 1.50",   // 49.99 * 3% = 1.4997 → rounds to 1.50
            "50.00, 2.50",   // 50   * 5% = 2.50
            "100.00, 5.00"   // 100  * 5% = 5.00
    })
    @DisplayName("calculateMatchAmount — tier boundary table")
    void calculateMatchAmount(String amount, String expectedMatch) {
        BigDecimal result = service.calculateMatchAmount(new BigDecimal(amount));
        assertThat(result).isEqualByComparingTo(new BigDecimal(expectedMatch));
    }
}

