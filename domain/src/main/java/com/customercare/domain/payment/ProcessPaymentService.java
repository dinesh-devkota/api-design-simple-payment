package com.customercare.domain.payment;

import com.customercare.domain.exception.AccountNotFoundException;
import com.customercare.domain.exception.InsufficientBalanceException;
import com.customercare.domain.exception.InvalidPaymentAmountException;
import com.customercare.domain.model.Account;
import com.customercare.domain.service.DueDateCalculationService;
import com.customercare.domain.service.MatchCalculationService;
import com.customercare.domain.spi.AccountSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;

/**
 * Domain use-case implementation for one-time payment processing.
 *
 * <p>Depends only on domain interfaces ({@link AccountSpi}, {@link MatchCalculationService},
 * {@link DueDateCalculationService}) — it has no knowledge of REST DTOs, Redis, or any
 * infrastructure concern.  Infrastructure wiring is handled by the bootstrap module.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessPaymentService implements ProcessPaymentUseCase {

    private final AccountSpi              accountSpi;
    private final MatchCalculationService matchCalculationService;
    private final DueDateCalculationService dueDateCalculationService;
    private final Clock                   clock;

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
        BigDecimal totalDeduction  = paymentAmount.add(matchAmount);

        if (totalDeduction.compareTo(previousBalance) > 0) {
            throw new InsufficientBalanceException(
                    "Payment $" + paymentAmount + " + match $" + matchAmount
                            + " exceeds balance $" + previousBalance);
        }

        BigDecimal newBalance      = previousBalance
                                         .subtract(totalDeduction)
                                         .setScale(2, RoundingMode.HALF_UP);
        LocalDate  today           = LocalDate.now(clock);
        LocalDate  nextDueDate     = dueDateCalculationService.calculateDueDate(today);

        account.setBalance(newBalance);
        accountSpi.save(account);

        log.info("Payment processed: userId={} payment={} match={}% matchAmt={} prev={} new={} due={}",
                userId, paymentAmount, matchPercentage, matchAmount, previousBalance, newBalance, nextDueDate);

        return new PaymentResult(
                userId,
                previousBalance,
                paymentAmount,
                matchPercentage,
                matchAmount,
                newBalance,
                nextDueDate,
                today);
    }
}

