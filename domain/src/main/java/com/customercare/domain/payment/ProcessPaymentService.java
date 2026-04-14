package com.customercare.domain.payment;

import com.customercare.domain.exception.AccountNotFoundException;
import com.customercare.domain.exception.InvalidPaymentAmountException;
import com.customercare.domain.model.Account;
import com.customercare.domain.service.DueDateCalculationService;
import com.customercare.domain.service.MatchCalculationService;
import com.customercare.domain.spi.AccountSpi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Domain use-case implementation for one-time payment processing.
 *
 * <p>Depends only on domain interfaces ({@link AccountSpi}, {@link MatchCalculationService},
 * {@link DueDateCalculationService}) — it has no knowledge of REST DTOs, Redis, or any
 * infrastructure concern.  Infrastructure wiring is handled by the bootstrap module.
 */
@Service
@RequiredArgsConstructor
public class ProcessPaymentService implements ProcessPaymentUseCase {

    private final AccountSpi              accountSpi;
    private final MatchCalculationService matchCalculationService;
    private final DueDateCalculationService dueDateCalculationService;

    @Override
    public PaymentResult process(String userId, BigDecimal paymentAmount) {
        if (paymentAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidPaymentAmountException(
                    "Payment amount must be greater than zero, but was: " + paymentAmount);
        }

        Account account = accountSpi.findById(userId)
                .orElseThrow(() -> new AccountNotFoundException(
                        "Account not found for userId: " + userId));

        BigDecimal previousBalance = account.getBalance();
        int        matchPercentage = matchCalculationService.getMatchPercentage(paymentAmount);
        BigDecimal matchAmount     = matchCalculationService.calculateMatchAmount(paymentAmount);
        BigDecimal newBalance      = previousBalance
                                         .subtract(paymentAmount.add(matchAmount))
                                         .setScale(2, RoundingMode.HALF_UP);
        LocalDate  nextDueDate     = dueDateCalculationService.calculateDueDate(LocalDate.now());

        account.setBalance(newBalance);
        accountSpi.save(account);

        return new PaymentResult(
                userId,
                previousBalance,
                paymentAmount,
                matchPercentage,
                matchAmount,
                newBalance,
                nextDueDate);
    }
}

