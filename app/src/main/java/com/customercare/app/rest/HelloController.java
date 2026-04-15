package com.customercare.app.rest;

import com.customercare.api.HealthApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin REST adapter for the smoke-test endpoint.
 *
 * <p>Implements the contract-first {@link HealthApi} interface generated from
 * {@code openapi.yaml}.
 */
@Slf4j
@RestController
public class HelloController implements HealthApi {

    @Override
    public ResponseEntity<String> hello() {
        log.debug("GET /hello");
        return ResponseEntity.ok("Hello from customer-care-api!");
    }
}

