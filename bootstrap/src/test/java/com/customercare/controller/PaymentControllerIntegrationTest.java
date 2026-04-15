package com.customercare.controller;

import com.customercare.infra.redis.entity.AccountEntity;
import com.customercare.infra.redis.repository.AccountRedisRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.*;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.support.TestPropertySourceUtils;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full Spring Boot integration tests for the payment endpoint.
 *
 * <p>An embedded Redis instance is started BEFORE the Spring {@code ApplicationContext}
 * is initialised (via {@link RedisTestInitializer}) to ensure the auto-configured
 * {@code RedisConnectionFactory} can connect successfully.
 *
 * <p>Tests seed data via {@link AccountRedisRepository} — reaching into the infra module
 * is acceptable in integration tests since this module depends on infra.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = PaymentControllerIntegrationTest.RedisTestInitializer.class)
class PaymentControllerIntegrationTest {

    // -------------------------------------------------------------------------
    // Embedded Redis lifecycle (started before Spring context)
    // -------------------------------------------------------------------------

    static RedisServer redisServer;

    public static class RedisTestInitializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        private static final int REDIS_TEST_PORT = 6381;

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            try {
                redisServer = new RedisServer(REDIS_TEST_PORT);
                redisServer.start();
            } catch (IOException e) {
                throw new RuntimeException("Failed to start embedded Redis on port " + REDIS_TEST_PORT, e);
            }
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                    "spring.data.redis.port=" + REDIS_TEST_PORT);
        }
    }

    @AfterAll
    static void stopRedis() {
        try {
            if (redisServer != null && redisServer.isActive()) {
                redisServer.stop();
            }
        } catch (IOException e) {
            System.err.println("Warning: failed to stop embedded Redis: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Test infrastructure
    // -------------------------------------------------------------------------

    @Autowired
    private TestRestTemplate restTemplate;

    /** Injected from infra to seed test data. Acceptable in integration tests. */
    @Autowired
    private AccountRedisRepository accountRedisRepository;

    @BeforeEach
    void setUp() {

        accountRedisRepository.deleteAll();
        accountRedisRepository.save(new AccountEntity("user-001", new BigDecimal("100.00")));
        accountRedisRepository.save(new AccountEntity("user-002", new BigDecimal("500.00")));
        accountRedisRepository.save(new AccountEntity("user-low",  new BigDecimal("50.00")));
    }

    // -------------------------------------------------------------------------
    // Happy path scenarios
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Happy path — mid-tier match: $10 payment on $100 balance → $89.70")
    void midTierMatch() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/one-time-payment",
                Map.of("userId", "user-001", "paymentAmount", 10.00),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(new BigDecimal(body.get("newBalance").toString()))
                .isEqualByComparingTo("89.70");
        assertThat(body.get("nextPaymentDueDate")).isNotNull();
    }

    @Test
    @DisplayName("Happy path — high-tier match: $75 payment on $500 balance → $421.25")
    void highTierMatch() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/one-time-payment",
                Map.of("userId", "user-002", "paymentAmount", 75.00),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(new BigDecimal(body.get("newBalance").toString()))
                .isEqualByComparingTo("421.25");
        assertThat(body.get("nextPaymentDueDate")).isNotNull();
    }

    @Test
    @DisplayName("Happy path — low-tier match: $5 payment on $50 balance → $44.95")
    void lowTierMatch() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/one-time-payment",
                Map.of("userId", "user-low", "paymentAmount", 5.00),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(new BigDecimal(body.get("newBalance").toString()))
                .isEqualByComparingTo("44.95");
        assertThat(body.get("nextPaymentDueDate")).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Weekend due-date shift scenarios — covered at domain layer:
    //   ProcessPaymentServiceTest  (process_dueDateSaturdayShiftedToMonday / SundayShiftedToMonday)
    //   DueDateCalculationServiceTest (full parametrized table)
    // The integration test only verifies the fields are present and wired through.
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Response contains previousBalance, newBalance, nextPaymentDueDate and paymentDate")
    void responseContainsExpectedFields() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/one-time-payment",
                Map.of("userId", "user-001", "paymentAmount", 10.00),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("nextPaymentDueDate")).isNotNull();
        assertThat(body.get("paymentDate")).isNotNull();
        assertThat(new BigDecimal(body.get("previousBalance").toString()))
                .isEqualByComparingTo("100.00");
        assertThat(body.containsKey("matchPercentage")).isFalse();
        assertThat(body.containsKey("userId")).isFalse();
    }

    // -------------------------------------------------------------------------
    // Validation failure scenarios
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Validation failure — paymentAmount = 0 → 400")
    void zeroPaymentAmount() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/one-time-payment",
                Map.of("userId", "user-001", "paymentAmount", 0),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Validation failure — paymentAmount negative → 400")
    void negativePaymentAmount() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/one-time-payment",
                Map.of("userId", "user-001", "paymentAmount", -5.00),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Validation failure — missing userId → 400")
    void missingUserId() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/one-time-payment",
                Map.of("paymentAmount", 10.00),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // -------------------------------------------------------------------------
    // Not found scenario
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Account not found — unknown userId → 404")
    void accountNotFound() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/one-time-payment",
                Map.of("userId", "unknown-user", "paymentAmount", 10.00),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status")).isEqualTo(404);
    }

    // -------------------------------------------------------------------------
    // Insufficient balance scenario
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Insufficient balance — payment + match exceeds balance → 422")
    void insufficientBalance() {
        // user-low has $50.00. Payment $50 → 5% match = $2.50 → total $52.50 > $50.00
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/one-time-payment",
                Map.of("userId", "user-low", "paymentAmount", 50.00),
                Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status")).isEqualTo(422);
    }

    // -------------------------------------------------------------------------
    // Idempotency scenario
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Idempotency — same key returns cached response without double-deducting")
    void idempotencyReplay() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "unique-key-123");

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(
                Map.of("userId", "user-001", "paymentAmount", 10.00), headers);

        // First request processes the payment
        ResponseEntity<Map> first = restTemplate.exchange(
                "/one-time-payment", HttpMethod.POST, request, Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Second request with same key returns cached result (no double-deduction)
        ResponseEntity<Map> second = restTemplate.exchange(
                "/one-time-payment", HttpMethod.POST, request, Map.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new BigDecimal(second.getBody().get("newBalance").toString()))
                .isEqualByComparingTo(new BigDecimal(first.getBody().get("newBalance").toString()));
    }
}
