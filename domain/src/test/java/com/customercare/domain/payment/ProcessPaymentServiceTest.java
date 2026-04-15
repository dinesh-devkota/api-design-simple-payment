package com.customercare.domain.payment;

import com.customercare.domain.exception.AccountNotFoundException;
import com.customercare.domain.exception.InsufficientBalanceException;
import com.customercare.domain.exception.InvalidPaymentAmountException;
import com.customercare.domain.model.Account;
import com.customercare.domain.service.impl.DueDateCalculationServiceImpl;
import com.customercare.domain.service.impl.MatchCalculationServiceImpl;
import com.customercare.domain.spi.AccountSpi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ProcessPaymentService} — no Spring context, no Redis.
 *
 * <p>{@link AccountSpi} is mocked; real calculation services are used to validate
 * tier logic end-to-end without touching infrastructure.
 *
 * <p>All fixed clocks use the consecutive April 2026 block so that
 * expected dates are easy to verify on a calendar:
 * <pre>
 *   2026-04-13 (Monday)   + 15 = 2026-04-28 (Tuesday)   — no shift  ← default
 *   2026-04-10 (Friday)   + 15 = 2026-04-25 (Saturday)  → 2026-04-27 (Monday)
 *   2026-04-11 (Saturday) + 15 = 2026-04-26 (Sunday)    → 2026-04-27 (Monday)
 * </pre>
 */
@ExtendWith(MockitoExtension.class)
class ProcessPaymentServiceTest {

    /** Fixed to 2026-04-13 (Monday) — +15 = 2026-04-28 (Tuesday, no shift). */
    private static final Clock FIXED_CLOCK = Clock.fixed(
            ZonedDateTime.of(2026, 4, 13, 12, 0, 0, 0, ZoneId.of("UTC")).toInstant(),
            ZoneId.of("UTC"));

    /** Fixed to 2026-04-10 (Friday) — +15 = 2026-04-25 (Saturday) → shifted to 2026-04-27 (Monday). */
    private static final Clock CLOCK_SATURDAY = Clock.fixed(
            ZonedDateTime.of(2026, 4, 10, 12, 0, 0, 0, ZoneId.of("UTC")).toInstant(),
            ZoneId.of("UTC"));

    /** Fixed to 2026-04-11 (Saturday) — +15 = 2026-04-26 (Sunday) → shifted to 2026-04-27 (Monday). */
    private static final Clock CLOCK_SUNDAY = Clock.fixed(
            ZonedDateTime.of(2026, 4, 11, 12, 0, 0, 0, ZoneId.of("UTC")).toInstant(),
            ZoneId.of("UTC"));

    @Mock
    private AccountSpi accountSpi;

    private ProcessPaymentUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ProcessPaymentService(
                accountSpi,
                new MatchCalculationServiceImpl(),
                new DueDateCalculationServiceImpl(),
                FIXED_CLOCK);
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Mid-tier match: $10 on $100 balance → newBalance=$89.70, matchPct=3")
    void process_midTier() {
        when(accountSpi.findById("user-1"))
                .thenReturn(Optional.of(new Account("user-1", new BigDecimal("100.00"))));
        when(accountSpi.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentResult result = useCase.process("user-1", new BigDecimal("10.00"));

        assertThat(result.matchPercentage()).isEqualTo(3);
        assertThat(result.matchAmount()).isEqualByComparingTo("0.30");
        assertThat(result.newBalance()).isEqualByComparingTo("89.70");
        assertThat(result.previousBalance()).isEqualByComparingTo("100.00");
        verify(accountSpi).save(any(Account.class));
    }

    @Test
    @DisplayName("High-tier match: $75 on $500 balance → newBalance=$421.25, matchPct=5")
    void process_highTier() {
        when(accountSpi.findById("user-2"))
                .thenReturn(Optional.of(new Account("user-2", new BigDecimal("500.00"))));
        when(accountSpi.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentResult result = useCase.process("user-2", new BigDecimal("75.00"));

        assertThat(result.matchPercentage()).isEqualTo(5);
        assertThat(result.matchAmount()).isEqualByComparingTo("3.75");
        assertThat(result.newBalance()).isEqualByComparingTo("421.25");
    }

    @Test
    @DisplayName("nextPaymentDueDate = 2026-04-28 (15 days from 2026-04-13 Monday — no shift)")
    void process_dueDateNoShift() {
        when(accountSpi.findById("user-1"))
                .thenReturn(Optional.of(new Account("user-1", new BigDecimal("100.00"))));
        when(accountSpi.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(useCase.process("user-1", new BigDecimal("10.00")).nextPaymentDueDate())
                .isEqualTo(LocalDate.of(2026, 4, 28));
    }

    @Test
    @DisplayName("Weekend shift — +15 lands on Saturday (2026-04-25), shifted to Monday (2026-04-27)")
    void process_dueDateSaturdayShiftedToMonday() {
        ProcessPaymentUseCase satUseCase = new ProcessPaymentService(
                accountSpi,
                new MatchCalculationServiceImpl(),
                new DueDateCalculationServiceImpl(),
                CLOCK_SATURDAY);
        when(accountSpi.findById("user-1"))
                .thenReturn(Optional.of(new Account("user-1", new BigDecimal("100.00"))));
        when(accountSpi.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentResult result = satUseCase.process("user-1", new BigDecimal("10.00"));

        assertThat(result.paymentDate()).isEqualTo(LocalDate.of(2026, 4, 10));
        assertThat(result.nextPaymentDueDate()).isEqualTo(LocalDate.of(2026, 4, 27));
    }

    @Test
    @DisplayName("Weekend shift — +15 lands on Sunday (2026-04-26), shifted to Monday (2026-04-27)")
    void process_dueDateSundayShiftedToMonday() {
        ProcessPaymentUseCase sunUseCase = new ProcessPaymentService(
                accountSpi,
                new MatchCalculationServiceImpl(),
                new DueDateCalculationServiceImpl(),
                CLOCK_SUNDAY);
        when(accountSpi.findById("user-1"))
                .thenReturn(Optional.of(new Account("user-1", new BigDecimal("100.00"))));
        when(accountSpi.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentResult result = sunUseCase.process("user-1", new BigDecimal("10.00"));

        assertThat(result.paymentDate()).isEqualTo(LocalDate.of(2026, 4, 11));
        assertThat(result.nextPaymentDueDate()).isEqualTo(LocalDate.of(2026, 4, 27));
    }

    // -------------------------------------------------------------------------
    // Error scenarios
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Zero paymentAmount → InvalidPaymentAmountException")
    void process_zeroAmount() {
        assertThatThrownBy(() -> useCase.process("user-1", BigDecimal.ZERO))
                .isInstanceOf(InvalidPaymentAmountException.class);
        verifyNoInteractions(accountSpi);
    }

    @Test
    @DisplayName("Negative paymentAmount → InvalidPaymentAmountException")
    void process_negativeAmount() {
        assertThatThrownBy(() -> useCase.process("user-1", new BigDecimal("-5.00")))
                .isInstanceOf(InvalidPaymentAmountException.class);
        verifyNoInteractions(accountSpi);
    }

    @Test
    @DisplayName("Unknown userId → AccountNotFoundException")
    void process_accountNotFound() {
        when(accountSpi.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.process("unknown", new BigDecimal("10.00")))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    @DisplayName("Payment + match exceeds balance → InsufficientBalanceException")
    void process_insufficientBalance() {
        when(accountSpi.findById("user-low"))
                .thenReturn(Optional.of(new Account("user-low", new BigDecimal("50.00"))));

        // $50 payment → 5% match = $2.50 → total $52.50 > $50.00 balance
        assertThatThrownBy(() -> useCase.process("user-low", new BigDecimal("50.00")))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("exceeds balance");
        verify(accountSpi, never()).save(any(Account.class));
    }

    @Test
    @DisplayName("Payment + match exactly equals balance → succeeds with $0.00")
    void process_exactBalance() {
        // $9.99 payment → 1% match = $0.10 → total $10.09
        when(accountSpi.findById("user-exact"))
                .thenReturn(Optional.of(new Account("user-exact", new BigDecimal("10.09"))));
        when(accountSpi.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentResult result = useCase.process("user-exact", new BigDecimal("9.99"));
        assertThat(result.newBalance()).isEqualByComparingTo("0.00");
    }
}

