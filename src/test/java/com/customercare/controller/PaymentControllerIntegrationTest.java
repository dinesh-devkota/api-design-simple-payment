package com.customercare.controller;

import com.customercare.model.Account;
import com.customercare.repository.AccountRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.support.TestPropertySourceUtils;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full Spring Boot integration tests for {@link PaymentController}.
 *
 * <p>An embedded Redis instance is started BEFORE the Spring {@code ApplicationContext}
 * is initialised (via {@link RedisTestInitializer}) to ensure the auto-configured
 * {@code RedisConnectionFactory} can connect successfully.
 *
 * <p>Tests always use a fresh Redis state (full {@code deleteAll()} in {@code @BeforeEach}).
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
            // Best-effort teardown — log and continue so test results are not hidden
            System.err.println("Warning: failed to stop embedded Redis: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Test infrastructure
    // -------------------------------------------------------------------------

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AccountRepository accountRepository;

    @BeforeEach
    void setUp() {
        accountRepository.deleteAll();
        accountRepository.save(new Account("user-001", new BigDecimal("100.00")));
        accountRepository.save(new Account("user-002", new BigDecimal("500.00")));
        accountRepository.save(new Account("user-low",  new BigDecimal("50.00")));
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
        assertThat(((Number) body.get("matchPercentage")).intValue()).isEqualTo(3);
        assertThat(new BigDecimal(body.get("newBalance").toString()))
                .isEqualByComparingTo("89.70");
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
        assertThat(((Number) body.get("matchPercentage")).intValue()).isEqualTo(5);
        assertThat(new BigDecimal(body.get("newBalance").toString()))
                .isEqualByComparingTo("421.25");
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
        assertThat(((Number) body.get("matchPercentage")).intValue()).isEqualTo(1);
        // 50.00 - 5.00 - 0.05 (1% of 5) = 44.95
        assertThat(new BigDecimal(body.get("newBalance").toString()))
                .isEqualByComparingTo("44.95");
    }

    @Test
    @DisplayName("Response contains nextPaymentDueDate as ISO-8601 string")
    void responseContainsDueDate() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/one-time-payment",
                Map.of("userId", "user-001", "paymentAmount", 10.00),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("nextPaymentDueDate")).isNotNull();
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

        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo(404);
    }
}

