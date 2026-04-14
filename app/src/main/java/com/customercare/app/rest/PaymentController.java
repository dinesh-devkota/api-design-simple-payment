package com.customercare.app.rest;

import com.customercare.api.PaymentApi;
import com.customercare.app.mapper.PaymentResponseMapper;
import com.customercare.domain.payment.PaymentResult;
import com.customercare.domain.payment.ProcessPaymentUseCase;
import com.customercare.domain.spi.IdempotencyStoreSpi;
import com.customercare.dto.OneTimePaymentRequest;
import com.customercare.dto.OneTimePaymentResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * <p>This controller depends only on the domain's {@link ProcessPaymentUseCase}
 * (primary port) — it has no direct knowledge of Redis or any other infrastructure.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PaymentController implements PaymentApi {

    private final ProcessPaymentUseCase processPaymentUseCase;
    private final PaymentResponseMapper paymentResponseMapper;
    private final IdempotencyStoreSpi   idempotencyStore;

    @Override
    public ResponseEntity<OneTimePaymentResponse> oneTimePayment(
            @Valid @RequestBody OneTimePaymentRequest request,
            String idempotencyKey) {

        log.info("POST /one-time-payment userId={} amount={} idempotencyKey={}",
                request.getUserId(), request.getPaymentAmount(), idempotencyKey);

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var cached = idempotencyStore.find(idempotencyKey, OneTimePaymentResponse.class);
            if (cached.isPresent()) {
                log.info("Idempotent replay: key={}", idempotencyKey);
                return ResponseEntity.ok(cached.get());
            }
        }

        PaymentResult result = processPaymentUseCase.process(
                request.getUserId(), request.getPaymentAmount());
        OneTimePaymentResponse response = paymentResponseMapper.toResponse(result);

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyStore.store(idempotencyKey, response);
        }

        return ResponseEntity.ok(response);
    }
}

