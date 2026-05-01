# Q&A 15 — Concurrency, Thread Safety, and Race Conditions

> The hardest questions in a payments interview — what can go wrong when two requests hit at the same time.

---

## Q1: Is `ProcessPaymentService` thread-safe?

**A:** **Partially.** The service itself has no mutable state — all its fields are `final` and injected at construction time. Method-local variables (`previousBalance`, `newBalance`, etc.) live on the call stack and are not shared between threads.

However, the **sequence of operations** is not atomic:

```java
Account account = accountSpi.findById(userId);    // 1. READ from Redis
// ... calculate new balance ...
account.setBalance(newBalance);
accountSpi.save(account);                          // 2. WRITE to Redis
```

Between steps 1 and 2, another thread can read the same account, calculate its own new balance based on the same old value, and write back — **overwriting the first thread's write**. This is the classic **lost update** concurrency bug.

---

## Q2: Give a concrete example of the race condition.

**A:** Two concurrent requests for `user-001` (starting balance: `$100.00`):

```
Thread A: READ  balance = $100.00
Thread B: READ  balance = $100.00     ← same old value
Thread A: CALC  newBalance = $89.70   ($10 payment + 3% match)
Thread B: CALC  newBalance = $89.70   (same payment, same calculation)
Thread A: WRITE balance = $89.70
Thread B: WRITE balance = $89.70      ← overwrites Thread A's write
```

Result: **Two $10 payments processed, but the balance only decreased once.** The user paid $20 but only $10 was deducted. Paytient loses $10.

Or in a different scenario:

```
Thread A: READ  balance = $100.00
Thread B: READ  balance = $100.00
Thread A: CALC  newBalance = $89.70  ($10 payment)
Thread B: CALC  newBalance = $4.75   ($95.24 payment — nearly the full balance)
Thread A: WRITE balance = $89.70
Thread B: WRITE balance = $4.75      ← correct for B, but ignores A's payment
```

Result: **Thread A's deduction is completely lost.** User's balance should be `$4.75 - $89.70 < 0` but is reported as `$4.75`.

---

## Q3: How would you fix the race condition in Redis?

**A:** Three options in increasing order of correctness:

### Option 1: Redis Lua Script (Atomic Compare-and-Swap)

Lua scripts execute atomically in Redis — no other command can run between them:

```lua
local key = KEYS[1]
local deduction = tonumber(ARGV[1])
local balance = tonumber(redis.call("HGET", key, "balance"))
if balance == nil then return -1 end          -- account not found
if balance < deduction then return -2 end     -- insufficient balance
local newBalance = balance - deduction
redis.call("HSET", key, "balance", newBalance)
return newBalance
```

This is the most performant solution for Redis. The entire check-and-deduct is one atomic Redis operation.

### Option 2: Redis WATCH / MULTI / EXEC (Optimistic Locking)

```java
redisTemplate.execute(new SessionCallback<>() {
    public Object execute(RedisOperations ops) {
        ops.watch("account:" + userId);       // watch the key
        BigDecimal balance = readBalance(ops);
        ops.multi();                          // start transaction
        ops.opsForHash().put("account:" + userId, "balance", newBalance);
        return ops.exec();                    // returns null if key changed since WATCH
    }
});
```

If `exec()` returns `null`, the key was modified by another client between `WATCH` and `EXEC` — retry the transaction. This is **optimistic locking**: no lock is held, but the transaction aborts and retries on conflict.

### Option 3: Distributed Lock (Redisson / Redis SETNX)

Acquire a distributed lock on the user's account before reading:
```java
RLock lock = redissonClient.getLock("account-lock:" + userId);
lock.lock(5, TimeUnit.SECONDS);   // timeout prevents deadlocks
try {
    // read, calculate, write
} finally {
    lock.unlock();
}
```

Correct, but adds latency and the risk of lock expiry under slow processing.

**Recommended for this use case:** Lua script (Option 1) — atomic, fast, no retry complexity.

---

## Q4: Is `RedisIdempotencyStore` thread-safe?

**A:** `StringRedisTemplate` operations are individually atomic at the Redis level (each command is atomic in Redis's single-threaded command processor). However, the **check-then-act** sequence in `IdempotencyGuard` is not:

```java
Optional<T> cached = idempotencyStore.find(key, type);  // 1. CHECK
if (cached.isPresent()) { return cached.get(); }
T result = supplier.get();                               // 2. ACT (do the work)
idempotencyStore.store(key, result);                     // 3. STORE
```

Two concurrent requests with the same key both read "not cached" at step 1, both execute step 2 (processing the payment twice), and both write at step 3. See Q&A 08 for the fix (Redis `SET NX PX`).

---

## Q5: Is `MatchCalculationServiceImpl` thread-safe?

**A:** **Yes, completely.** The class has:
- No instance state (no fields other than `static final` constants).
- No shared mutable data.
- Pure functions: same input always produces same output.

`static final BigDecimal TEN = BigDecimal.valueOf(10)` — `BigDecimal` is immutable, so multiple threads reading it simultaneously is safe.

---

## Q6: Is `DueDateCalculationServiceImpl` thread-safe?

**A:** **Yes.** Same reasoning — no mutable state, `LocalDate` is immutable, `plusDays()` returns a new object. The `switch` expression reads no shared state.

---

## Q7: What is the visibility of bean instances in Spring? How many instances of `ProcessPaymentService` exist?

**A:** By default, Spring beans are **singleton-scoped** — one instance per application context, shared across all threads handling incoming requests.

This is why thread safety of the bean's own state matters enormously. `ProcessPaymentService` is safe because its fields are immutable after injection. If it had a mutable field (e.g., a counter), all concurrent requests would race to update it.

If you needed per-request state in a Spring bean, you'd use `@Scope("request")` (for web request scope) or `@Scope("prototype")` (new instance per injection point). Neither is needed here.

---

## Q8: What is the role of `final` fields in thread safety?

**A:** `final` fields in Java have a special memory model guarantee: **once a constructor completes, any thread that reads a `final` field is guaranteed to see its initialized value**, without needing explicit synchronization.

```java
@RequiredArgsConstructor
public class ProcessPaymentService {
    private final AccountSpi              accountSpi;     // ← final
    private final MatchCalculationService matchCalc;      // ← final
    private final Clock                   clock;          // ← final
}
```

Once Spring finishes constructing `ProcessPaymentService`, all threads will see the fully-initialized references for these fields. Without `final`, the JVM's memory model does not guarantee other threads see the values without a `volatile` or `synchronized` keyword.

---

## Q9: Could `BigDecimal` arithmetic cause thread-safety issues?

**A:** No. `BigDecimal` is **immutable** — every arithmetic operation (`add`, `subtract`, `multiply`, `divide`) returns a new `BigDecimal` instance. No shared state is mutated. Multiple threads can call `.add()` on the same `BigDecimal` object simultaneously without any issue.

This is in contrast to `BigInteger` (also immutable) and unlike `MutableBigDecimal` (an internal class that is mutable, but never used directly).

---

## Q10: What concurrency improvements would you make before going to production?

1. **Lua script for atomic balance deduction** — eliminate the read-modify-write race condition entirely.
2. **Idempotency `SET NX PX`** — atomic set-if-absent for the idempotency cache to prevent duplicate processing.
3. **Connection pool tuning** — configure Lettuce's connection pool (`spring.data.redis.lettuce.pool.*`) to handle concurrent requests without exhausting connections.
4. **Rate limiting per userId** — prevent a single user from submitting thousands of concurrent requests with different idempotency keys, each racing to deduct from the same balance.
5. **Optimistic locking retry with backoff** — if using WATCH/MULTI/EXEC, implement exponential backoff retry for transaction conflicts rather than failing immediately.
6. **Load test** — run a concurrent load test (k6, Gatling) with the same `userId` to observe and measure race conditions before they occur in production.
