# Q&A 07 — Infrastructure Layer: Redis, Adapters, and Entity Mapping

> Covers `AccountAdapter`, `AccountEntity`, `AccountEntityMapper`, `RedisConfig`, `AccountRedisRepository`, and `seed-local-data.sh`.

---

## Q1: What is the `infra` module responsible for?

**A:** The `infra` module is the **secondary adapter layer** — it implements the domain's SPI interfaces using concrete technology (Redis). It is the only module that knows about:
- Redis connection details and configuration (`RedisConfig`).
- Spring Data Redis annotations (`@RedisHash`, `@Id`).
- The `AccountEntity` persistence representation.
- The `AccountRedisRepository` Spring Data interface.

If the persistence store changes (e.g., to PostgreSQL), only this module needs to change. The `domain`, `app`, and `bootstrap` modules are unaffected.

---

## Q2: What does `AccountAdapter` do and why does it exist as a separate class?

**A:**
```java
@Component
public class AccountAdapter implements AccountSpi {
    private final AccountRedisRepository accountRedisRepository;
    private final AccountEntityMapper    accountEntityMapper;

    public Optional<Account> findById(String userId) {
        return accountRedisRepository.findById(userId)
                .map(accountEntityMapper::toDomain);
    }

    public Account save(Account account) {
        return accountEntityMapper.toDomain(
                accountRedisRepository.save(accountEntityMapper.toEntity(account)));
    }
}
```

It exists to **translate between the domain's language** (`Account`, `AccountSpi`) **and Redis's language** (`AccountEntity`, `AccountRedisRepository`). Without this class, the domain would need to import Redis-specific types — breaking the dependency inversion.

`AccountAdapter` is the only class that needs to change when Redis is replaced with another store.

---

## Q3: Why is there an `AccountEntity` class separate from `Account`?

**A:** `AccountEntity` carries all Redis/Spring Data annotations:
```java
@RedisHash("account")  // tells Spring Data to store under key "account:{id}"
public class AccountEntity {
    @Id
    private String userId;
    private BigDecimal balance;
}
```

`Account` (domain) carries none. This separation:
- Keeps the domain model free of framework annotations.
- Allows the persistence schema to evolve independently of the domain model (e.g., adding a `createdAt` field to Redis without adding it to the domain object).
- Makes `Account` independently testable — no Spring context needed.

---

## Q4: How does Spring Data Redis store `AccountEntity`?

**A:** `@RedisHash("account")` tells Spring Data Redis to use a **Hash** data structure. The seed script shows the exact Redis commands that reproduce this format:

```bash
HSET "account:user-001" "_class" "com.customercare.infra.redis.entity.AccountEntity" \
     "userId"  "user-001" \
     "balance" "100.00"
SADD "account" "user-001"     # maintains the "all accounts" index
```

- Key: `account:{userId}` (e.g., `account:user-001`)
- Fields: `_class` (for polymorphic deserialization), `userId`, `balance`
- A secondary set key `account` stores all known user IDs for full-scan operations.

`BigDecimal` is stored as a string (`"100.00"`) — Spring Data Redis serializes it via its default `toString()`.

---

## Q5: What is `AccountEntityMapper` and why use MapStruct for it?

**A:**
```java
@Mapper(componentModel = "spring")
public interface AccountEntityMapper {
    Account       toDomain(AccountEntity entity);
    AccountEntity toEntity(Account account);
}
```

MapStruct generates `AccountEntityMapperImpl` at compile time. Both `Account` and `AccountEntity` have identically named fields (`userId`, `balance`), so no `@Mapping` annotations are required.

**Why MapStruct?**
- Zero runtime reflection — generated code is plain getter/setter calls.
- Compile-time safety — if a field is added to `Account` but forgotten in `AccountEntity`, MapStruct warns.
- Eliminates boilerplate — without MapStruct, the adapter would have four lines of manual field assignment per conversion.

---

## Q6: What does `RedisConfig` do and why is it needed?

**A:**
```java
@Bean
public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
    RedisTemplate<String, Object> template = new RedisTemplate<>();
    template.setKeySerializer(new StringRedisSerializer());
    template.setHashKeySerializer(new StringRedisSerializer());
    template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
    template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
    return template;
}
```

By default, Spring Boot's auto-configured `RedisTemplate` uses Java serialization for values, producing binary keys and values that are unreadable in RedisInsight. `RedisConfig` overrides this with:
- `StringRedisSerializer` for keys — human-readable key names.
- `GenericJackson2JsonRedisSerializer` for values — JSON values, readable and debuggable via RedisInsight.

This `RedisTemplate` is used by `RedisIdempotencyStore`. `AccountRedisRepository` uses its own Spring Data serialization path (controlled by `@RedisHash`).

---

## Q7: Why does the seed script use `redis-cli` inside `docker exec` rather than a Java seeder?

**A:** The seed script is a **developer tool** — it runs once to populate demo data for local Swagger testing. Using `redis-cli` directly is:
- Simple and dependency-free — no Java compilation required.
- Immediate — runs in seconds.
- Transparent — the exact Redis commands are visible and reproducible.
- Re-runnable — run it any time to reset balances to known values.

A Java seeder (e.g., `ApplicationRunner` with profile `local`) would also work and would be safer (using the same serialization as the app), but adds complexity for a dev-only tool.

**Potential issue with the script:** The seed script manually constructs the Redis hash in the format Spring Data Redis expects (including `_class`). If the entity class is renamed or moved, the script must be updated. A Java seeder using the repository would be immune to this fragility.

---

## Q8: What are the advantages and disadvantages of using Redis for account storage?

**Advantages:**
| Advantage | Detail |
|---|---|
| Extremely fast reads/writes | Sub-millisecond latency for simple key lookups |
| Simple data model | A `Hash` per account is easy to understand |
| Built-in TTL | Useful for idempotency keys (24h TTL) |
| Embedded Redis for tests | `embedded-redis` library enables no-Docker integration tests |

**Disadvantages:**
| Disadvantage | Detail |
|---|---|
| No ACID transactions | Redis does not support multi-key atomic commits; concurrent updates to the same account can race |
| Volatile by default | Data loss on restart unless `appendonly yes` is configured (it is in `docker-compose.yml`) |
| Limited query capabilities | Cannot `SELECT * WHERE balance > 100` — must know the key |
| Not relational | No foreign-key integrity, joins, or referential constraints |
| Scaling cost | Redis Cluster adds significant operational complexity |

---

## Q9: What improvements could be made to the infrastructure layer?

1. **Optimistic locking / atomic compare-and-swap** — use a Redis Lua script to atomically check balance and deduct, preventing concurrent double-spend:
   ```lua
   local balance = redis.call("HGET", key, "balance")
   if tonumber(balance) >= tonumber(deduction) then
     redis.call("HSET", key, "balance", balance - deduction)
     return 1
   end
   return 0
   ```

2. **Connection pooling tuning** — configure Lettuce connection pool settings in `application.yml` for production throughput (`lettuce.pool.max-active`, etc.).

3. **Redis Sentinel / Cluster** — for high availability, configure Spring Data Redis to use Sentinel for automatic failover.

4. **Migrate to PostgreSQL** — for ACID guarantees, payment history, and audit trails, a relational database (with JPA/Hibernate) would be preferable in production. The adapter-based architecture makes this swap localised to the `infra` module.

5. **Health indicator** — add a custom `HealthIndicator` bean that checks Redis connectivity so `/actuator/health` returns `DOWN` when Redis is unavailable.

6. **Repository-based seeding** — replace `seed-local-data.sh` with an `ApplicationRunner` annotated `@Profile("local")` that uses `AccountRedisRepository.save()` to seed data, removing the dependency on manual `redis-cli` format knowledge.
