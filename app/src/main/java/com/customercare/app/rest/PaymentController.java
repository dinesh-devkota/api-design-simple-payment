package com.customercare.app.rest;

import com.customercare.api.PaymentApi;
import com.customercare.app.idempotency.IdempotencyGuard;
import com.customercare.app.mapper.PaymentResponseMapper;
import com.customercare.domain.payment.ProcessPaymentUseCase;
import com.customercare.dto.OneTimePaymentRequest;
import com.customercare.dto.OneTimePaymentResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin REST adapter for one-time payment operations.
 *
 * <p>Implements the contract-first {@link PaymentApi} interface generated from
 * {@code openapi.yaml}.  All Swagger/OpenAPI annotations, request mappings, and
 * response codes live on the generated interface.
 *
 * <p>This controller has a single responsibility: translate HTTP ↔ domain.
 * Idempotency cache logic lives in {@link IdempotencyGuard}; business rules
 * live in {@link ProcessPaymentUseCase}.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PaymentController implements PaymentApi {

    private static final String MDC_USER_ID = "userId";

    private final ProcessPaymentUseCase processPaymentUseCase;
    private final PaymentResponseMapper paymentResponseMapper;
    private final IdempotencyGuard      idempotencyGuard;

    @Override
    public ResponseEntity<OneTimePaymentResponse> oneTimePayment(
            @Valid @RequestBody OneTimePaymentRequest request,
            String idempotencyKey) {

        long start = System.currentTimeMillis();
        MDC.put(MDC_USER_ID, request.getUserId());

        try {
            log.info("POST /one-time-payment received: userId={} amount={} idempotencyKey={}",
                    request.getUserId(), request.getPaymentAmount(),
                    idempotencyKey != null ? idempotencyKey : "(none)");

            OneTimePaymentResponse response = idempotencyGuard.resolve(
                    idempotencyKey,
                    OneTimePaymentResponse.class,
                    () -> paymentResponseMapper.toResponse(
                            processPaymentUseCase.process(
                                    request.getUserId(), request.getPaymentAmount())));

            log.info("POST /one-time-payment completed: userId={} prevBalance={} newBalance={} dueDate={} elapsedMs={}",
                    request.getUserId(), response.getPreviousBalance(), response.getNewBalance(),
                    response.getNextPaymentDueDate(), System.currentTimeMillis() - start);

            return ResponseEntity.ok(response);

        } finally {
            MDC.remove(MDC_USER_ID);
        }
    }
}


