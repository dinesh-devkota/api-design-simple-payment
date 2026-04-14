package com.customercare.infra.redis.adapter;

import com.customercare.domain.spi.IdempotencyStoreSpi;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed idempotency store with a 24-hour TTL.
 *
 * <p>Values are serialized to JSON via Jackson so that any DTO can be
 * stored and retrieved by type. Serialization errors are logged and
 * swallowed — a failing idempotency cache must never block a payment.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisIdempotencyStore implements IdempotencyStoreSpi {

    private static final Duration TTL    = Duration.ofHours(24);
    private static final String   PREFIX = "idempotency:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper        objectMapper;

    @Override
    public <T> Optional<T> find(String key, Class<T> type) {
        String json = redisTemplate.opsForValue().get(PREFIX + key);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, type));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize cached response for idempotency key={}", key, e);
            return Optional.empty();
        }
    }

    @Override
    public void store(String key, Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(PREFIX + key, json, TTL);
            log.debug("Idempotency response cached: key={}", key);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize response for idempotency key={}", key, e);
        }
    }
}
