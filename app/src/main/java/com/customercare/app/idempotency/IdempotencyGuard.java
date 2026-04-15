package com.customercare.app.idempotency;

import com.customercare.domain.spi.IdempotencyStoreSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Encapsulates the idempotency cache-check / execute / store pattern so that
 * controllers remain thin HTTP adapters with no conditional idempotency logic.
 *
 * <p>Usage:
 * <pre>{@code
 * Response r = idempotencyGuard.resolve(idempotencyKey, Response.class,
 *         () -> doTheActualWork());
 * }</pre>
 *
 * <p>If {@code key} is blank or null the supplier is called directly with no
 * caching.  If a cached entry exists it is returned immediately (cache hit
 * logged at INFO).  Otherwise the supplier is executed and its result is
 * stored before being returned.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyGuard {

    private final IdempotencyStoreSpi idempotencyStore;

    /**
     * Resolves a response idempotently.
     *
     * @param key      the {@code Idempotency-Key} header value; may be null or blank
     * @param type     the expected response type (used for deserialization)
     * @param supplier called only on a cache miss or when {@code key} is absent
     * @param <T>      response type
     * @return cached or freshly computed response
     */
    public <T> T resolve(String key, Class<T> type, Supplier<T> supplier) {
        if (key == null || key.isBlank()) {
            return supplier.get();
        }

        Optional<T> cached = idempotencyStore.find(key, type);
        if (cached.isPresent()) {
            log.info("Idempotency cache hit: key={}", key);
            return cached.get();
        }

        T result = supplier.get();
        idempotencyStore.store(key, result);
        return result;
    }
}

