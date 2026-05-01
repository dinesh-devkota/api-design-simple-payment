# Q&A 30 — System Design: Scaling This Payment Service

> What happens when this single-endpoint service needs to handle 10x, 100x, 1000x traffic? This file covers horizontal scaling, caching strategy, database evolution, and distributed systems tradeoffs.

---

## Q1: This service has a race condition. Design an atomic balance deduction in Redis.

**A:** The current flow is:

```
1. GET balance
2. Calculate new balance (in Java)
3. SET new balance
```

Two concurrent requests for the same user can both read the same balance and both write back — one deduction is lost.

**Solution 1 — Lua Script (atomic in single-node Redis):**
```lua
local key = KEYS[1]
local deduction = ARGV[1]
local balance = redis.call('HGET', key, 'balance')
if not balance then
    return redis.error_reply('ACCOUNT_NOT_FOUND')
end
local new_balance = tonumber(balance) - tonumber(deduction)
if new_balance < 0 then
    return redis.error_reply('INSUFFICIENT_BALANCE')
end
redis.call('HSET', key, 'balance', tostring(new_balance))
return tostring(new_balance)
```

Redis executes Lua scripts atomically — no other command can interleave. This eliminates the race condition.

**Solution 2 — Optimistic locking (WATCH/MULTI/EXEC):**
```java
return redisTemplate.execute(new SessionCallback<>() {
    public Object execute(RedisOperations ops) {
        ops.watch("account:" + userId);
        String balance = (String) ops.opsForHash().get("account:" + userId, "balance");
        BigDecimal newBalance = new BigDecimal(balance).subtract(deduction);
        ops.multi();
        ops.opsForHash().put("account:" + userId, "balance", newBalance.toString());
        return ops.exec();  // returns null if WATCH key was modified — retry needed
    }
});
```

WATCH/MULTI/EXEC provides optimistic concurrency — if the key was modified between WATCH and EXEC, the transaction fails and the caller retries.

**Solution 3 — Distributed lock (Redisson):**
```java
RLock lock = redissonClient.getLock("lock:account:" + userId);
lock.lock(5, TimeUnit.SECONDS);
try {
    // read-modify-write safely
} finally {
    lock.unlock();
}
```

Adds latency (lock acquisition) but works across multiple Redis nodes.

**For this project:** Lua script is the best choice — atomic, no lock contention, no retry logic needed.

---

## Q2: The service needs to handle 10,000 requests/second. What breaks first?

**A:** Bottlenecks in order:

1. **Single Redis node** — Redis is single-threaded for command processing. A single node handles ~100,000 simple GET/SET operations/second. For complex operations (Lua scripts, transactions): ~10,000-50,000/second. At 10K RPS with multiple Redis calls per request: Redis becomes the bottleneck.

2. **Single JVM** — Spring Boot with embedded Tomcat defaults to 200 threads. At 50ms per request average: 200/0.05 = 4,000 RPS max before thread pool saturation.

3. **No connection pooling tuning** — Lettuce (the Redis client) uses a single Netty connection by default. Under heavy load, connection exhaustion causes timeouts.

**Scaling approach:**
- Horizontal scale the JVM (multiple instances behind a load balancer) — stateless Spring app scales easily.
- Redis Cluster for horizontal Redis scaling (sharding by key slot).
- Tune Lettuce connection pool: `spring.data.redis.lettuce.pool.max-active=50`.
- Enable virtual threads: `spring.threads.virtual.enabled=true` — removes Tomcat thread pool as bottleneck.

---

## Q3: A product requirement says "store full payment history for every transaction." How do you redesign the data model?

**A:** The current model: one `Account` record with a mutable `balance`. History is impossible.

**New model:**

**Event Sourcing approach:**
```
account:{userId}:balance → current balance (computed or cached)
payment:{paymentId} → { userId, amount, matchAmount, dueDate, processedAt, previousBalance, newBalance }
```

Or with relational DB (better for history):
```sql
CREATE TABLE accounts (
    user_id VARCHAR(50) PRIMARY KEY,
    balance NUMERIC(18, 2) NOT NULL
);

CREATE TABLE payment_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id VARCHAR(50) NOT NULL REFERENCES accounts(user_id),
    payment_amount NUMERIC(18, 2) NOT NULL,
    match_amount NUMERIC(18, 2) NOT NULL,
    previous_balance NUMERIC(18, 2) NOT NULL,
    new_balance NUMERIC(18, 2) NOT NULL,
    payment_due_date DATE NOT NULL,
    idempotency_key VARCHAR(255),
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_payment_user_id ON payment_transactions(user_id, processed_at DESC);
```

**Architectural change:** Add `PaymentHistorySpi` interface. Add `GET /payments/{userId}/history` endpoint. Redis alone cannot serve this — requires a relational database or a time-series store.

---

## Q4: How would you implement rate limiting so one user cannot submit 1,000 payments in a second?

**A:** **Redis-based sliding window rate limiter:**

```java
// In a new RateLimitFilter or within IdempotencyGuard
public boolean isAllowed(String userId) {
    String key = "ratelimit:" + userId;
    long now = System.currentTimeMillis();
    long windowMs = 1000;  // 1 second window
    int maxRequests = 5;
    
    // Redis Sorted Set: score = timestamp, member = unique request ID
    redisTemplate.opsForZSet().removeRangeByScore(key, 0, now - windowMs);  // trim old entries
    Long count = redisTemplate.opsForZSet().zCard(key);
    
    if (count != null && count >= maxRequests) {
        return false;  // rate limited
    }
    
    redisTemplate.opsForZSet().add(key, UUID.randomUUID().toString(), now);
    redisTemplate.expire(key, Duration.ofSeconds(2));
    return true;
}
```

**Or use a library:** Spring Cloud Gateway (if this becomes a gateway) has built-in Redis rate limiting via `RequestRateLimiter` filter. Bucket4j is another option for in-process token bucket rate limiting.

**HTTP response:** `429 Too Many Requests` with `Retry-After` header.

---

## Q5: How would you add support for multiple currencies without breaking the existing API?

**A:** **Backwards-compatible approach:**

1. **API versioning:** Add `v2` endpoint: `POST /v2/one-time-payment` with a `currency` field. Keep `v1` for existing clients, defaulting to `USD`.

2. **Domain changes:**
   - `Account.balance` becomes a `Map<Currency, BigDecimal>` or separate `AccountBalance` entities per currency.
   - `ProcessPaymentService` validates that `paymentAmount.currency == account.currency`.
   - `MatchCalculationService` stays currency-agnostic (works on `BigDecimal` amounts).

3. **Exchange rate concern:** If users pay in different currencies, you need a `CurrencyConversionSpi` — a secondary port that fetches live exchange rates from an external service (Fixer.io, ECB, etc.).

4. **Existing data migration:** All existing accounts default to `USD`. A migration script updates the Redis hash to add `currency: USD`.

---

## Q6: The `IdempotencyGuard` uses a 24-hour TTL. What is the right TTL and how do you reason about it?

**A:** TTL selection depends on:

- **Client retry window:** How long does the client retry the same request? If retries happen within 5 minutes, a 24-hour TTL is overkill — wastes Redis memory.
- **Business requirement:** "A payment cannot be processed twice for the same idempotency key within X hours" — X is the business rule.
- **Failure detection time:** If the client crashes and never receives the response, how long before it gives up and resubmits with a new key? This is the minimum TTL.

**Typical payment industry practice:** 24-72 hours for payment idempotency (Stripe uses 24 hours). The reasoning: network timeouts can cause clients to retry up to 24 hours after the original request in some implementations.

**Improvements:**
- Make TTL configurable: `@Value("${app.idempotency.ttl-hours:24}")`.
- Use a shorter TTL for test environments.
- Log when an idempotency hit is served — unusual frequency could indicate client bugs.

---

## Q7: How would you design a `POST /batch-payment` endpoint that processes 100 payments atomically?

**A:** This is a significantly harder problem:

**Naive approach:** Loop over 100 payments, call `processPayment()` 100 times. Problems:
- Not atomic — 50 can succeed and 50 can fail.
- No rollback for the first 50.

**Better approach — Saga pattern (eventual consistency):**
1. Create a `BatchPaymentJob` entity with status `PENDING`.
2. Publish each individual payment as a message to a queue (Azure Service Bus / Kafka).
3. A payment processor consumes and processes each message with idempotency.
4. Each processed payment updates the `BatchPaymentJob` status.
5. When all complete: `BatchPaymentJob.status = COMPLETED`.
6. If any fail: compensating transaction (refund to balance) for completed ones.

**Or — Two-phase commit (if using a relational database):**
1. Deduct all balances in a single SQL transaction.
2. Commit atomically.
3. Write audit records in the same transaction.

**The key insight:** Redis does not support distributed transactions across multiple keys — you need a relational database or a message queue for true batch atomicity.

---

## Q8: What observability would you add and how?

**A:** Three pillars of observability:

**Metrics (Micrometer → Prometheus → Grafana):**
```java
// Add spring-boot-starter-actuator + micrometer-registry-prometheus
@Service
public class ProcessPaymentService {
    private final MeterRegistry meterRegistry;
    
    public PaymentResult process(String userId, BigDecimal amount) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            // ... process ...
            meterRegistry.counter("payments.processed", "status", "success").increment();
            return result;
        } catch (Exception e) {
            meterRegistry.counter("payments.processed", "status", "failure", "error", e.getClass().getSimpleName()).increment();
            throw e;
        } finally {
            sample.stop(meterRegistry.timer("payments.processing.time"));
        }
    }
}
```

**Distributed tracing (OpenTelemetry → Jaeger / Azure Monitor):**
Add `spring-boot-starter-actuator` + `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`. Each request gets a `traceId` — visible in logs and trace explorer.

**Structured logging:**
```java
log.info("Payment processed",
    kv("userId", userId),
    kv("paymentAmount", amount),
    kv("matchAmount", matchAmount),
    kv("newBalance", newBalance),
    kv("traceId", MDC.get("traceId")));
```

JSON logging with logstash-logback-encoder → shipped to Elasticsearch → Kibana dashboard.

---

## Q9: How would you handle Redis downtime without losing payments?

**A:** Current behaviour: Redis unavailable → `RedisConnectionFailureException` → unhandled → 500 to client.

**Option 1 — Circuit breaker (Resilience4j):**
```java
@CircuitBreaker(name = "redis", fallbackMethod = "processPaymentFallback")
public PaymentResult process(...) { ... }

public PaymentResult processPaymentFallback(String userId, BigDecimal amount, Exception e) {
    // fallback: queue the payment for later processing
    // or: return 503 Service Unavailable with Retry-After header
}
```

**Option 2 — Fallback to PostgreSQL:**
If Redis is down, write to a PostgreSQL `fallback_payments` table. A background job re-processes them when Redis recovers.

**Option 3 — Graceful degradation:**
Return `503 Service Unavailable` with `Retry-After: 30` header. The client retries after 30 seconds. Simple and honest — better than accepting the payment and silently failing to process it.

**Option 4 — Redis Sentinel / Cluster:**
Prevents downtime entirely via failover (Sentinel) or automatic shard failover (Cluster). Primary prevention is better than fallback handling.

---

## Q10: You are the tech lead. A junior dev proposes replacing Redis with PostgreSQL. How do you evaluate this?

**A:** Evaluate on four dimensions:

**Performance:**
- Redis: sub-millisecond reads/writes for simple key lookups.
- PostgreSQL: ~1-5ms for indexed reads with connection pool. At 10K RPS: connection pool becomes a bottleneck.
- **Verdict:** Redis faster for simple KV access patterns. PostgreSQL needed for complex queries.

**Data model fit:**
- Current: `account:{userId}` → hash of fields. Perfect for Redis `HGET`/`HSET`.
- With history requirement: rows with relationships — perfect for PostgreSQL.
- **Verdict:** Depends on the data model.

**Operational complexity:**
- Redis: simpler ops, managed services (Azure Cache for Redis, Elasticache).
- PostgreSQL: managed services (Azure Database for PostgreSQL) also simple. Better tooling (pgAdmin, psql), better backup/restore, ACID guarantees.
- **Verdict:** PostgreSQL has more mature operational tooling.

**Team expertise:**
- If the team knows SQL well and Redis poorly: PostgreSQL reduces operational risk.
- If the team knows Redis well: keep what works.

**Recommendation:** Move to PostgreSQL for the payment data (relational, auditable, ACID). Keep Redis for idempotency caching (TTL-based, ephemeral, high-throughput key lookups). Two tools, each used for what they do best.
