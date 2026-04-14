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
            "2022-03-14, 2022-03-29, +15=Tuesday  no-shift",
            "2022-04-08, 2022-04-25, +15=Saturday shift+2 to Monday",
            "2022-05-07, 2022-05-23, +15=Sunday   shift+1 to Monday",
            "2022-05-01, 2022-05-16, +15=Monday   no-shift",
            "2022-05-02, 2022-05-17, +15=Tuesday  no-shift",
            "2022-05-03, 2022-05-18, +15=Wednesday no-shift",
            "2022-05-04, 2022-05-19, +15=Thursday  no-shift",
            "2022-05-05, 2022-05-20, +15=Friday    no-shift"
    })
    @DisplayName("calculateDueDate — weekday shift table")
    void calculateDueDate(String paymentDateStr, String expectedDueDateStr, String notes) {
        assertThat(service.calculateDueDate(LocalDate.parse(paymentDateStr)))
                .as("Due date for %s (%s)", paymentDateStr, notes)
                .isEqualTo(LocalDate.parse(expectedDueDateStr));
    }
}

