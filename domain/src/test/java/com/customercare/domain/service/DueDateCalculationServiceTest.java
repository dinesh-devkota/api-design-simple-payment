package com.customercare.domain.service;

import com.customercare.domain.service.impl.DueDateCalculationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link DueDateCalculationServiceImpl} — no Spring context.
 */
class DueDateCalculationServiceTest {

    private DueDateCalculationService service;

    @BeforeEach
    void setUp() {
        service = new DueDateCalculationServiceImpl();
    }

    @ParameterizedTest(name = "paymentDate={0} → dueDate={1} ({2})")
    @CsvSource({
            // Consecutive block Apr 10–16 2026. 15 mod 7 = 1, so +15 shifts the day-of-week forward by one.
            // Weekend cases first, then every weekday outcome to prove no false shift.
            "2026-04-10, 2026-04-27, +15=Saturday  shift+2 to Monday",
            "2026-04-11, 2026-04-27, +15=Sunday    shift+1 to Monday",
            "2026-04-12, 2026-04-27, +15=Monday    no-shift",
            "2026-04-13, 2026-04-28, +15=Tuesday   no-shift",
            "2026-04-14, 2026-04-29, +15=Wednesday no-shift",
            "2026-04-15, 2026-04-30, +15=Thursday  no-shift",
            "2026-04-16, 2026-05-01, +15=Friday    no-shift"
    })
    @DisplayName("calculateDueDate — weekday shift table")
    void calculateDueDate(String paymentDateStr, String expectedDueDateStr, String notes) {
        assertThat(service.calculateDueDate(LocalDate.parse(paymentDateStr)))
                .as("Due date for %s (%s)", paymentDateStr, notes)
                .isEqualTo(LocalDate.parse(expectedDueDateStr));
    }
}

