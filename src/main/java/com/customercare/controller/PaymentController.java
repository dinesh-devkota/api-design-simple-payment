package com.customercare.controller;

import com.customercare.api.PaymentApi;
import com.customercare.dto.OneTimePaymentRequest;
import com.customercare.dto.OneTimePaymentResponse;
import com.customercare.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin REST controller for one-time payment operations.
 *
 * <p>Implements the contract-first {@link PaymentApi} interface generated from
 * {@code openapi.yaml}. All Swagger/OpenAPI annotations, request mappings, and
 * response codes live on the generated interface — this class contains only
 * business-delegation logic.
 */
@RestController
@RequiredArgsConstructor
public class PaymentController implements PaymentApi {

    private final PaymentService paymentService;

    @Override
    public ResponseEntity<OneTimePaymentResponse> oneTimePayment(
            @Valid @RequestBody OneTimePaymentRequest oneTimePaymentRequest) {
        return ResponseEntity.ok(paymentService.processOneTimePayment(oneTimePaymentRequest));
    }
}
