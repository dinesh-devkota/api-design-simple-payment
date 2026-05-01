# Q&A 08 — Idempotency: `IdempotencyGuard` and `RedisIdempotencyStore`

> Covers what idempotency is, why it matters for payments, how it's implemented, and improvements.

---

## Q1: What is idempotency and why does a payment API need it?

**A:** An operation is **idempotent** if performing it multiple times produces the same result as performing it once. For a payment API this is critical:

- A mobile client submits `POST /one-time-payment`.
- The server processes it successfully but the network drops before the response arrives.
- The client, seeing no response, retries the same request.
- **Without idempotency:** the balance is deducted twice — the user is charged double.
- **With idempotency:** the second request detects it was already processed and returns the original response without touching the balance.

The client controls this by sending a unique `Idempotency-Key` header with each request. If it retries, it reuses the same key.

---

## Q2: What does `IdempotencyGuard` do and why is it a separate class?

**A:**
```java
public <T> T resolve(String key, Class<T> type, Supplier<T> supplier) {
    if (key == null || key.isBlank()) {
        return supplier.get();          // no key → no caching
    }
    Optional<T> cached = idempotencyStore.find(key, type);
    if (cached.isPresent()) {
        log.info("Idempotency cache hit: key={}", key);
        return cached.get();            // cache hit → return without re-processing
    }
    T result = supplier.get();          // cache miss → execute the real work
    idempotencyStore.store(key, result);
    return result;
}
```

It is separate from `PaymentController` because:
- **Single Responsibility** — the controller should not contain conditional cache logic.
- **Reusability** — any future endpoint (e.g., `POST /refund`) can use the same guard without copying logic.
- **Testability** — the guard can be unit-tested independently by mocking `IdempotencyStoreSpi`.

The pattern is a **Strategy / Template Method**: the "real work" is passed as a `Supplier<T>` lambda, so the guard knows nothing about payment logic.

---

## Q3: What does `RedisIdempotencyStore` do and how does it store data?

**A:** It implements `IdempotencyStoreSpi` using Redis:

**Storing:**
```java
String json = objectMapper.writeValueAsString(value); // serialize DTO to JSON
redisTemplate.opsForValue().set(PREFIX + key, json, TTL); // store with 24h TTL
```

**Finding:**
```java
String json = redisTemplate.opsForValue().get(PREFIX + key); // look up by key
return Optional.of(objectMapper.readValue(json, type));      // deserialize to typed DTO
```

- **Key format:** `idempotency:{clientKey}` — the prefix prevents collisions with `account:` keys.
- **TTL: 24 hours** — after 24 hours the key expires automatically and a fresh request would re-process. This matches the convention for most payment APIs.
- **Serialization:** Jackson JSON — human-readable, debuggable in RedisInsight, and type-safe via `Class<T>`.

---

## Q4: Why are serialization errors in `RedisIdempotencyStore` swallowed rather than re-thrown?

**A:**
```java
// store():
catch (JsonProcessingException e) {
    log.error("Failed to serialize response for idempotency key={}", key, e);
    // swallowed — do NOT re-throw
}

// find():
catch (JsonProcessingException e) {
    log.error("Failed to deserialize cached response for idempotency key={}", key, e);
    return Optional.empty();  // treat as cache miss
}
```

The principle is: **a failing idempotency cache must never block a payment**. Idempotency is a reliability mechanism, not a core business rule. If caching fails:
- The payment was still processed correctly.
- The worst case is a duplicate charge if the client retries — but that's preferable to failing the entire payment because JSON serialization threw an unexpected error.
- The error is logged at `ERROR` level so an alert can fire and the root cause can be investigated.

---

## Q5: What happens if the same `Idempotency-Key` is used by two concurrent requests?

**A:** This is a **race condition**. Both requests arrive simultaneously:
1. Both check the cache — both get `Optional.empty()` (cache miss).
2. Both execute `supplier.get()` — both process the payment.
3. Both store their result — the second `store` overwrites the first.

The balance is deducted twice. This is the **TOCTOU** (Time-Of-Check-Time-Of-Use) problem.

**Current state:** Not handled — the implementation is not race-condition-safe.

**Proper fix:** Use a Redis atomic `SET NX PX` (Set if Not eXists with expiry):
```
SET idempotency:{key} "processing" NX PX 86400000
```
If the key does not exist, set it to `"processing"` and proceed. If it already exists, wait or return "in progress". Replace `"processing"` with the real response once done.

---

## Q6: What is the `IdempotencyStoreSpi` interface and why is it in the `domain` module?

**A:**
```java
// domain module
public interface IdempotencyStoreSpi {
    <T> Optional<T> find(String key, Class<T> type);
    void store(String key, Object value);
}
```

It lives in `domain` because:
- The `IdempotencyGuard` (in `app`) depends on it.
- `app` depends on `domain`, not on `infra`.
- If `IdempotencyStoreSpi` were in `infra`, `app` would have to import `infra`, violating the dependency direction.
- The Redis implementation (`RedisIdempotencyStore`) is in `infra`, which correctly imports both `domain` (for the interface) and Redis dependencies.

---

## Q7: What are the advantages and disadvantages of this idempotency implementation?

**Advantages:**
| Advantage | Detail |
|---|---|
| Transparent to business logic | `ProcessPaymentService` has no idempotency code |
| Generic | Works with any response type via `Class<T>` |
| TTL-based cleanup | Redis expires keys automatically — no cleanup job needed |
| Readable | Cache-check / execute / store pattern is clear in 10 lines |

**Disadvantages:**
| Disadvantage | Detail |
|---|---|
| Race condition | Concurrent requests with the same key can both process |
| No request fingerprinting | The same key with a different body is treated as a duplicate (should be rejected as a conflict) |
| 24h TTL is fixed | Not configurable per endpoint or client |
| Serialization coupling | The cached value is a JSON-serialized DTO — if the DTO schema changes, old cached values are undeserializable |

---

## Q8: What improvements could be made?

1. **Atomic set-if-absent** — use `SET NX PX` to prevent the concurrent duplicate race condition.
2. **Request fingerprinting** — store a hash of the request body alongside the key; return `409 Conflict` if the same key arrives with a different body (indicates misuse by the client).
3. **Configurable TTL** — inject TTL as a property:
   ```yaml
   app.idempotency.ttl-hours: 24
   ```
4. **Schema version in cache key** — append a schema version to the key (e.g., `idempotency:v1:{clientKey}`) so DTO schema changes invalidate old cached entries rather than causing deserialization errors.
5. **In-progress sentinel** — store a `"PROCESSING"` sentinel immediately on cache miss, before executing the supplier. If a second request arrives while the first is processing, it can return `202 Accepted` or retry after a delay, rather than racing.
6. **Separate TTL for error responses** — currently errors are not cached (only successes stored). You might want to cache `422 Insufficient Balance` responses too, so retries don't hammer the database for a permanently invalid request.
