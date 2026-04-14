package com.customercare.app.rest;

import com.customercare.api.HealthApi;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin REST adapter for the smoke-test endpoint.
 *
 * <p>Implements the contract-first {@link HealthApi} interface generated from
 * {@code openapi.yaml}.
 */
@RestController
public class HelloController implements HealthApi {

    @Override
    public ResponseEntity<String> hello() {
        return ResponseEntity.ok("Hello from customer-care-api!");
    }
}

