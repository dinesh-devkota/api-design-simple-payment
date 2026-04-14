package com.customercare.app.rest;

import com.customercare.api.PaymentApi;
import com.customercare.app.mapper.PaymentResponseMapper;
import com.customercare.domain.payment.PaymentResult;
import com.customercare.domain.payment.ProcessPaymentUseCase;
import com.customercare.dto.OneTimePaymentRequest;
import com.customercare.dto.OneTimePaymentResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
@RestController
@RequiredArgsConstructor
public class PaymentController implements PaymentApi {

    private final ProcessPaymentUseCase processPaymentUseCase;
    private final PaymentResponseMapper paymentResponseMapper;

    @Override
    public ResponseEntity<OneTimePaymentResponse> oneTimePayment(
            @Valid @RequestBody OneTimePaymentRequest request) {
        PaymentResult result = processPaymentUseCase.process(
                request.getUserId(), request.getPaymentAmount());
        return ResponseEntity.ok(paymentResponseMapper.toResponse(result));
    }
}

