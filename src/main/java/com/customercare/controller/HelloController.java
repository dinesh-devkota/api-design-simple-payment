package com.customercare.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Health", description = "Liveness / smoke-test endpoints")
@RestController
@RequestMapping("/hello")
public class HelloController {

    @Operation(summary = "Hello World", description = "Confirms the service is running and reachable.")
    @GetMapping
    public ResponseEntity<String> hello() {
        return ResponseEntity.ok("Hello from customer-care-api!");
    }
}

