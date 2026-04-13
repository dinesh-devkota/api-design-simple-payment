package com.customercare.service;

import com.customercare.dto.OneTimePaymentRequest;
import com.customercare.dto.OneTimePaymentResponse;
import com.customercare.exception.AccountNotFoundException;
import com.customercare.exception.InvalidPaymentAmountException;
import com.customercare.model.Account;
import com.customercare.repository.AccountRepository;
import com.customercare.mapper.PaymentMapperImpl;
import com.customercare.service.impl.DueDateCalculationServiceImpl;
import com.customercare.service.impl.MatchCalculationServiceImpl;
import com.customercare.service.impl.PaymentServiceImpl;
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
 * Unit tests for {@link PaymentServiceImpl} using Mockito — no Spring context, no Redis.
 *
 * <p>DTOs ({@link OneTimePaymentRequest}, {@link OneTimePaymentResponse}) are generated
 * from {@code openapi.yaml}; construction uses the generated fluent setters.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private AccountRepository accountRepository;

    private PaymentService service;

    @BeforeEach
    void setUp() {
        service = new PaymentServiceImpl(
                accountRepository,
                new MatchCalculationServiceImpl(),
                new DueDateCalculationServiceImpl(),
                new PaymentMapperImpl());  // MapStruct-generated mapper — no Spring context needed
    }

    // -------------------------------------------------------------------------
    // Helpers — use generated fluent setters
    // -------------------------------------------------------------------------

    private static OneTimePaymentRequest request(String userId, String amount) {
        return new OneTimePaymentRequest()
                .userId(userId)
                .paymentAmount(new BigDecimal(amount));
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Mid-tier match: $10 payment on $100 balance → newBalance=$89.70, matchPct=3")
    void processPayment_midTier() {
        when(accountRepository.findById("user-1"))
                .thenReturn(Optional.of(new Account("user-1", new BigDecimal("100.00"))));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        OneTimePaymentResponse resp = service.processOneTimePayment(request("user-1", "10.00"));

        assertThat(resp.getMatchPercentage()).isEqualTo(3);
        assertThat(resp.getMatchAmount()).isEqualByComparingTo("0.30");
        assertThat(resp.getNewBalance()).isEqualByComparingTo("89.70");
        assertThat(resp.getPreviousBalance()).isEqualByComparingTo("100.00");
        verify(accountRepository).save(any(Account.class));
    }

    @Test
    @DisplayName("High-tier match: $75 payment on $500 balance → newBalance=$421.25, matchPct=5")
    void processPayment_highTier() {
        when(accountRepository.findById("user-2"))
                .thenReturn(Optional.of(new Account("user-2", new BigDecimal("500.00"))));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        OneTimePaymentResponse resp = service.processOneTimePayment(request("user-2", "75.00"));

        assertThat(resp.getMatchPercentage()).isEqualTo(5);
        assertThat(resp.getMatchAmount()).isEqualByComparingTo("3.75");
        assertThat(resp.getNewBalance()).isEqualByComparingTo("421.25");
    }

    @Test
    @DisplayName("Due date is populated in response")
    void processPayment_dueDatePresent() {
        when(accountRepository.findById("user-1"))
                .thenReturn(Optional.of(new Account("user-1", new BigDecimal("100.00"))));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        OneTimePaymentResponse resp = service.processOneTimePayment(request("user-1", "10.00"));

        assertThat(resp.getNextPaymentDueDate()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Error scenarios
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Zero paymentAmount → InvalidPaymentAmountException")
    void processPayment_zeroAmount() {
        assertThatThrownBy(() -> service.processOneTimePayment(
                new OneTimePaymentRequest().userId("user-1").paymentAmount(BigDecimal.ZERO)))
                .isInstanceOf(InvalidPaymentAmountException.class);

        verifyNoInteractions(accountRepository);
    }

    @Test
    @DisplayName("Negative paymentAmount → InvalidPaymentAmountException")
    void processPayment_negativeAmount() {
        assertThatThrownBy(() -> service.processOneTimePayment(request("user-1", "-5.00")))
                .isInstanceOf(InvalidPaymentAmountException.class);

        verifyNoInteractions(accountRepository);
    }

    @Test
    @DisplayName("Unknown userId → AccountNotFoundException")
    void processPayment_accountNotFound() {
        when(accountRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.processOneTimePayment(request("unknown", "10.00")))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessageContaining("unknown");
    }
}
