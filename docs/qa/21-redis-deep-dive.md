# Q&A 21 — Redis Deep Dive: Data Structures, Commands, Spring Data, and Trade-offs

> How Redis actually stores data in this project, what happens under the hood, and every hard question about Redis in a payment context.

---

## Q1: What Redis data structures does this project use and why?

**A:** Two data structures:

### Hash (for account storage)
Spring Data Redis's `@RedisHash` stores each `AccountEntity` as a Redis **Hash** (a map of field→value pairs):

```
Key:    account:user-001
Type:   Hash
Fields: _class  → com.customercare.infra.redis.entity.AccountEntity
        userId  → user-001
        balance → 100.00
```

A Hash is appropriate because:
- An account has multiple fields (userId, balance) that can be read/updated individually with `HGET`/`HSET`.
- Hashes are more memory-efficient than storing a JSON string for small objects.

### String (for idempotency cache)
`RedisIdempotencyStore` uses a Redis **String** (a single value):

```
Key:    idempotency:test-key-001
Type:   String
Value:  {"previousBalance":100.00,"newBalance":89.70,...}   ← JSON
TTL:    86400 seconds (24 hours)
```

A String is appropriate because:
- The entire response is cached as an atomic unit (no need for individual field access).
- TTL-based expiry is straightforward on String keys.

---

## Q2: What secondary index does Spring Data Redis create and why?

**A:** Spring Data Redis creates a secondary **Set** to track all entity IDs for `findAll()` operations:

```
Key:   account
Type:  Set
Value: {user-001, user-002, user-low}
```

The seed script mirrors this manually:
```bash
SADD "account" "user-001"
```

This set is how Spring Data Redis implements `CrudRepository.findAll()` — it reads all members of the `account` set, then fetches each `account:{id}` hash.

**Implication:** Every `save()` operation writes to two keys atomically (the hash and the set). If a failure occurs between the two writes, the set and the hashes are inconsistent. In Redis, there is no multi-key atomic transaction in standalone mode without Lua scripts.

---

## Q3: What is `appendonly yes` in `docker-compose.yml` and why does it matter?

**A:**
```yaml
command: redis-server --appendonly yes
```

By default, Redis uses **RDB (snapshotting)** — it periodically writes a point-in-time snapshot to disk. Data written since the last snapshot is lost if Redis crashes.

**AOF (Append-Only File)** writes every write command to a log file as it happens. On restart, Redis replays the AOF to reconstruct the dataset. This reduces the data loss window from "since the last snapshot" to "since the last fsync" (typically ≤ 1 second).

For a payment service, losing even one processed payment is unacceptable. `appendonly yes` is the minimum persistence requirement for production.

**Additional setting for production:**
```
appendfsync everysec   # fsync every second (default) — up to 1 second of data loss
appendfsync always     # fsync every write — zero data loss but slower
appendfsync no         # OS decides — fastest but most data loss risk
```

---

## Q4: How does `StringRedisTemplate` differ from `RedisTemplate<String, Object>`?

**A:**

| Template | Key type | Value type | Serialization |
|---|---|---|---|
| `StringRedisTemplate` | String | String | Both keys and values are plain UTF-8 strings |
| `RedisTemplate<String, Object>` | String | Object | Keys: `StringRedisSerializer`; Values: `GenericJackson2JsonRedisSerializer` |

`RedisIdempotencyStore` uses `StringRedisTemplate` — it stores manually-serialized JSON strings (`objectMapper.writeValueAsString(value)`). This gives full control over the serialization format.

`AccountAdapter` uses Spring Data Redis (`AccountRedisRepository`) which uses its own serialization path (Hash field values as strings, with `_class` for polymorphism).

**Why two different templates?** Spring Data Redis's Hash storage and the manual JSON String storage serve different purposes with different access patterns. Mixing them into one serialization strategy would require complex configuration.

---

## Q5: What is `GenericJackson2JsonRedisSerializer` and what is the `_class` field?

**A:** `GenericJackson2JsonRedisSerializer` serializes Java objects to JSON and adds a `_class` field:

```json
{"_class":"com.customercare.infra.redis.entity.AccountEntity","userId":"user-001","balance":100.00}
```

The `_class` field stores the fully-qualified class name and is used during deserialization to instantiate the correct Java type. This enables **polymorphic deserialization** — if you store multiple subclasses of a base class, Redis can reconstruct the correct type on read.

**Security risk:** `_class` with Jackson's default polymorphic deserialization is a known deserialization vulnerability (Jackson CVE-2017-7525 and family). An attacker who can write arbitrary values to Redis could inject a `_class` pointing to a gadget class and execute arbitrary code. Mitigation: use `allowlist`-based type mapping rather than arbitrary class names.

---

## Q6: What is a Redis TTL and how is it set on the idempotency key?

**A:**
```java
redisTemplate.opsForValue().set(PREFIX + key, json, TTL);
// TTL = Duration.ofHours(24)
```

Under the hood this executes:
```
SET idempotency:{key} "{json}" EX 86400
```

`EX 86400` tells Redis to **expire and delete the key after 86400 seconds (24 hours)**. After expiry:
- `GET idempotency:{key}` returns `null`.
- The `IdempotencyGuard` treats this as a cache miss.
- A client retrying after 24 hours would re-process the payment.

TTL is Redis's built-in solution to cache eviction — no separate cleanup job is needed.

**To inspect a TTL in Redis:**
```
TTL idempotency:test-key-001     # returns seconds remaining, or -1 (no TTL), or -2 (key not found)
```

---

## Q7: What is the difference between `HSET`, `HGET`, `HGETALL`, and `HMSET`?

**A:**

| Command | Description | Example |
|---|---|---|
| `HSET key field value` | Set one field in a hash | `HSET account:user-001 balance 89.70` |
| `HGET key field` | Get one field from a hash | `HGET account:user-001 balance` |
| `HGETALL key` | Get all fields and values | `HGETALL account:user-001` |
| `HMSET key f1 v1 f2 v2` | Set multiple fields at once (deprecated since Redis 4.0; `HSET` now accepts multiple) | — |
| `HDEL key field` | Delete a field | `HDEL account:user-001 balance` |

The seed script uses `HSET` with multiple field-value pairs (the modern form) to populate all fields in one command.

---

## Q8: What happens if two Spring Boot instances try to save the same account simultaneously?

**A:** Redis processes commands **sequentially in a single thread** — each individual command (e.g., `HSET`) is atomic. However, the Spring Data Redis `save()` operation is **not** a single Redis command — it involves:
1. `HSET account:user-001 balance 89.70` (update the hash)
2. `SADD account user-001` (update the set index)

Between commands from two concurrent Spring instances:
```
Instance A: HSET account:user-001 balance 89.70
Instance B: HSET account:user-001 balance 95.00   ← overwrites A's write
Instance A: SADD account user-001
Instance B: SADD account user-001                  ← idempotent (already a member)
```

Result: Instance A's balance update is **lost**. This is the race condition described in Q&A 15.

---

## Q9: What is Redis Sentinel and when would this project need it?

**A:** Redis Sentinel is a high-availability solution for Redis. It monitors a primary Redis node and one or more replica nodes. If the primary fails:
1. Sentinel elects a new primary from the replicas.
2. Sentinel notifies clients of the new primary address.
3. Spring Data Redis (via Lettuce) automatically reconnects to the new primary.

**When this project needs it:**
- Production: a single Redis node is a **single point of failure**. If it crashes, all balance reads/writes fail — payments are unavailable.
- Sentinel requires at minimum one primary + one replica + three sentinel processes.

Configuration in `application-prod.yml`:
```yaml
spring:
  data:
    redis:
      sentinel:
        master: mymaster
        nodes: sentinel1:26379, sentinel2:26379, sentinel3:26379
```

---

## Q10: Why is Redis not a substitute for a relational database in a production payment system?

**A:**

| Requirement | Redis | Relational DB (PostgreSQL) |
|---|---|---|
| ACID transactions | No (single-key atomic only) | Full ACID |
| Multi-key atomic updates | Lua scripts only | `BEGIN`/`COMMIT` |
| Payment history / audit trail | Not natively (no append-only table) | Immutable rows with timestamps |
| Complex queries (JOIN, aggregate) | Not supported | Full SQL |
| Referential integrity | No foreign keys | `FOREIGN KEY` constraints |
| Point-in-time recovery | Limited (AOF replay) | WAL-based PITR |
| Regulatory compliance | Difficult (no audit log) | Standard (append-only audit table) |

**Redis's role in this project is appropriate for Iteration 1:** fast, simple, schema-less storage for a demo. `openapi.yaml` explicitly notes "Iteration 2 (planned) — Oracle-backed persistence." Redis is a stepping stone, not the final persistence layer.

The hexagonal architecture (`AccountSpi` abstraction) makes this migration localised to the `infra` module — exactly why the abstraction exists.
