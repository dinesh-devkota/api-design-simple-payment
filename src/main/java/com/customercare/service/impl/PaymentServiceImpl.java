package com.customercare.service.impl;

import com.customercare.dto.OneTimePaymentRequest;
import com.customercare.dto.OneTimePaymentResponse;
import com.customercare.exception.AccountNotFoundException;
import com.customercare.exception.InvalidPaymentAmountException;
import com.customercare.mapper.PaymentMapper;
import com.customercare.model.Account;
import com.customercare.repository.AccountRepository;
import com.customercare.service.DueDateCalculationService;
import com.customercare.service.MatchCalculationService;
import com.customercare.service.PaymentCalculationResult;
import com.customercare.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Orchestrates the one-time payment workflow.
 * Response assembly is delegated to {@link PaymentMapper} (MapStruct-generated).
 */
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final AccountRepository         accountRepository;
    private final MatchCalculationService   matchCalculationService;
    private final DueDateCalculationService dueDateCalculationService;
    private final PaymentMapper             paymentMapper;

    @Override
    public OneTimePaymentResponse processOneTimePayment(OneTimePaymentRequest request) {
        BigDecimal paymentAmount = request.getPaymentAmount();

        if (paymentAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidPaymentAmountException(
                    "Payment amount must be greater than zero, but was: " + paymentAmount);
        }

        Account account = accountRepository.findById(request.getUserId())
                .orElseThrow(() -> new AccountNotFoundException(
                        "Account not found for userId: " + request.getUserId()));

        BigDecimal previousBalance = account.getBalance();
        int        matchPercentage = matchCalculationService.getMatchPercentage(paymentAmount);
        BigDecimal matchAmount     = matchCalculationService.calculateMatchAmount(paymentAmount);
        BigDecimal newBalance      = previousBalance
                                         .subtract(paymentAmount.add(matchAmount))
                                         .setScale(2, RoundingMode.HALF_UP);
        LocalDate  nextDueDate     = dueDateCalculationService.calculateDueDate(LocalDate.now());

        account.setBalance(newBalance);
        accountRepository.save(account);

        return paymentMapper.toPaymentResponse(
                request,
                previousBalance,
                new PaymentCalculationResult(matchPercentage, matchAmount, newBalance, nextDueDate));
    }
}
