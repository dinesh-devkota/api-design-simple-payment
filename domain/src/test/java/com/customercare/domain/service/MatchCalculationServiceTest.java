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

    @ParameterizedTest(name = "amount={0} → matchAmount={1}")
    @CsvSource({
            "0.01,  0.00",
            "9.99,  0.10",
            "10.00, 0.30",
            "49.99, 1.50",
            "50.00, 2.50",
            "100.00, 5.00"
    })
    @DisplayName("calculateMatchAmount — tier boundary table")
    void calculateMatchAmount(String amount, String expectedMatch) {
        assertThat(service.calculateMatchAmount(new BigDecimal(amount)))
                .isEqualByComparingTo(new BigDecimal(expectedMatch));
    }
}

