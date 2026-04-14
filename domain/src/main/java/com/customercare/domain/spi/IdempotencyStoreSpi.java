package com.customercare.domain.spi;

import java.util.Optional;

/**
 * Secondary port (SPI) — idempotency key-value store.
 *
 * <p>Allows callers to cache a response by key and retrieve it later,
 * preventing duplicate processing of the same request. The infra module
 * provides a Redis-backed implementation with a 24-hour TTL.
 */
public interface IdempotencyStoreSpi {

    /**
     * Retrieves a previously cached value.
     *
     * @param key  the idempotency key
     * @param type the expected value type (used for deserialization)
     * @return the cached value, or empty if not found
     */
    <T> Optional<T> find(String key, Class<T> type);

    /**
     * Caches a value under the given key.
     *
     * @param key   the idempotency key
     * @param value the value to cache (serialized internally)
     */
    void store(String key, Object value);
}
