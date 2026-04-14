package com.customercare.domain.payment;

import com.customercare.domain.exception.AccountNotFoundException;
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
 */
@ExtendWith(MockitoExtension.class)
class ProcessPaymentServiceTest {

    @Mock
    private AccountSpi accountSpi;

    private ProcessPaymentUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ProcessPaymentService(
                accountSpi,
                new MatchCalculationServiceImpl(),
                new DueDateCalculationServiceImpl());
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
    @DisplayName("nextPaymentDueDate is populated")
    void process_dueDatePresent() {
        when(accountSpi.findById("user-1"))
                .thenReturn(Optional.of(new Account("user-1", new BigDecimal("100.00"))));
        when(accountSpi.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(useCase.process("user-1", new BigDecimal("10.00")).nextPaymentDueDate())
                .isNotNull();
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
}

