package com.customercare.controller;

import com.customercare.api.HealthApi;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin REST controller for the smoke-test endpoint.
 *
 * <p>Implements the contract-first {@link HealthApi} interface generated from
 * {@code openapi.yaml}. The {@code @GetMapping("/hello")} and all OpenAPI
 * annotations live on the generated interface.
 */
@RestController
public class HelloController implements HealthApi {

    @Override
    public ResponseEntity<String> hello() {
        return ResponseEntity.ok("Hello from customer-care-api!");
    }
}
