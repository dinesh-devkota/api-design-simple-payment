# Q&A 17 — Design Patterns Used in This Codebase

> Named patterns from the Gang of Four and enterprise catalogue — where each appears, why it was chosen, and the trade-offs.

---

## 1. Adapter Pattern

### Where
`AccountAdapter` and `RedisIdempotencyStore` in the `infra` module.

### What it does
Converts the interface of one class (`AccountRedisRepository`, `StringRedisTemplate`) into another interface that the client expects (`AccountSpi`, `IdempotencyStoreSpi`).

```
Client (domain)  →  AccountSpi  ←  AccountAdapter  →  AccountRedisRepository  →  Redis
```

The domain speaks `AccountSpi`. Redis speaks Spring Data `CrudRepository`. The adapter translates between them so neither has to change.

### Why used
Isolates the domain from infrastructure details. Replacing Redis with a SQL database means writing a new adapter — not touching the domain.

### Trade-off
Every adapter requires a mapper (`AccountEntityMapper`) to translate between domain objects and persistence entities. MapStruct handles this with compile-time generation, but the indirection still exists.

---

## 2. Port and Adapter (Hexagonal Architecture)

### Where
The entire four-module structure.

### What it does
Organises the system around a domain hexagon with inbound ports (APIs, driven by external actors) and outbound ports (SPIs, driving external systems):
- **Inbound**: `ProcessPaymentUseCase` (REST controller drives it).
- **Outbound**: `AccountSpi`, `IdempotencyStoreSpi` (domain drives Redis through them).

### Why used
Achieves maximum isolation of business rules from delivery mechanisms and infrastructure.

### Trade-off
More classes, more indirection, more files. Justified when the domain is expected to grow and the infrastructure is expected to change (as documented in `openapi.yaml` Iteration 2 notes).

---

## 3. Template Method Pattern

### Where
`IdempotencyGuard.resolve()`.

### What it does
Defines the skeleton of an algorithm (check cache → execute → store), deferring the "execute" step to a caller-supplied lambda:

```java
public <T> T resolve(String key, Class<T> type, Supplier<T> supplier) {
    // Step 1: check cache (fixed)
    Optional<T> cached = idempotencyStore.find(key, type);
    if (cached.isPresent()) return cached.get();
    // Step 2: execute — VARIABLE, provided by caller
    T result = supplier.get();
    // Step 3: store (fixed)
    idempotencyStore.store(key, result);
    return result;
}
```

The controller passes the "real work" as a lambda: `() -> paymentResponseMapper.toResponse(processPaymentUseCase.process(...))`.

### Why used
Keeps the idempotency logic in one place. Any future endpoint gets idempotency protection by calling `idempotencyGuard.resolve(...)` with its own supplier — no duplication.

### Trade-off
The `Supplier<T>` is opaque — if the supplier throws an exception, the caller must handle it outside the guard. Errors are not cached (a failed payment is not idempotent).

---

## 4. Strategy Pattern

### Where
`MatchCalculationService` interface + `MatchCalculationServiceImpl`.

### What it does
Defines a family of algorithms (match tier calculations), encapsulates each one, and makes them interchangeable. `ProcessPaymentService` depends on the `MatchCalculationService` interface — a different implementation (e.g., configurable tiers loaded from a database) could be injected without changing the use-case.

### Why used
Decouples the tier-calculation algorithm from the payment orchestration. The algorithm can evolve (new tiers, configurable tiers, A/B test different tier structures) without changing `ProcessPaymentService`.

### Trade-off
The current single implementation makes the pattern feel like premature abstraction. The payoff comes when a second implementation (e.g., `ConfigurableMatchCalculationService` loaded from YAML) is added.

---

## 5. Factory Method Pattern

### Where
`@Bean` methods in `ClockConfig` and `RedisConfig`.

### What it does
Delegates the creation of objects (`Clock`, `RedisTemplate`) to factory methods, allowing subclasses or configurations to override the created type:

```java
@Bean
@ConditionalOnProperty(name = "app.fixed-date")
public Clock fixedClock(...) { return Clock.fixed(...); }

@Bean
@ConditionalOnMissingBean(Clock.class)
public Clock systemClock() { return Clock.systemDefaultZone(); }
```

The caller (`ProcessPaymentService`) never calls `new Clock(...)` — it receives a `Clock` from the factory.

### Why used
Allows the `Clock` implementation to vary by environment (fixed in tests/demo, system in production) without changing any client code.

### Trade-off
Spring's `@ConditionalOn*` mechanism handles the factory selection automatically, but the conditions must be carefully designed to avoid both beans registering simultaneously.

---

## 6. Null Object Pattern

### Where
`Optional<Account>` returned by `AccountSpi.findById()`.

### What it does
Represents "absence of a value" as a typed object (`Optional.empty()`) rather than `null`, forcing the caller to explicitly handle the absent case:

```java
Account account = accountSpi.findById(userId)
        .orElseThrow(() -> new AccountNotFoundException("..."));
```

### Why used
`null` return values are a common source of `NullPointerException`. `Optional` makes the possibility of absence explicit in the method signature and forces the caller to decide what to do.

### Trade-off
`Optional` should not be used as a field type or method parameter — only as a return type. Misuse (e.g., `Optional<Account>` as a field in `Account`) causes serialization issues with Jackson and Spring Data.

---

## 7. Value Object Pattern (DDD)

### Where
`PaymentResult` (Java record), `Account` (Lombok `@Data`).

### What it does
Represents a concept by its value rather than its identity. Two `PaymentResult` records with the same fields are equal regardless of whether they are the same object instance.

`PaymentResult` is a pure value object — no identity, no lifecycle, immutable.
`Account` is closer to an **Entity** in DDD terms — it has identity (`userId`) and a lifecycle (balance changes over time).

### Why used
Value objects are safer to pass around (no aliasing issues), easier to test (equality by value, not by reference), and more expressive (a `PaymentResult` record self-documents what data it carries).

### Trade-off
Java records are immutable — if `Account` were a record, `setBalance()` would not exist and the use-case would need to create a new `Account` instance for every payment, which is the correct approach but requires a slightly different flow.

---

## 8. Facade Pattern

### Where
`ProcessPaymentService` acts as a facade over `MatchCalculationService`, `DueDateCalculationService`, `AccountSpi`, and `Clock`.

### What it does
Provides a simplified interface (`process(userId, paymentAmount) → PaymentResult`) that hides the complexity of coordinating multiple subsystems.

The controller calls one method and gets a complete result — it has no knowledge of how match tiers work, how due dates are calculated, or how Redis is accessed.

### Why used
Reduces the cognitive load on callers. The controller remains thin because all orchestration is encapsulated behind the use-case facade.

### Trade-off
The facade can become a "God class" if too much logic is added directly to it rather than delegated to smaller services.

---

## 9. Proxy Pattern

### Where
Spring's AOP-based annotation processing (`@Slf4j`, `@RequiredArgsConstructor`, Spring's CGLIB proxy for `@Configuration`).

### What it does
Creates a proxy object that intercepts calls to the target and adds cross-cutting behaviour (logging, transaction management, security checks) without the target knowing.

Spring's `@Configuration` classes are wrapped in a CGLIB proxy so that `@Bean` methods called from within the configuration class return the singleton bean rather than creating a new instance.

### Why used
AOP proxying is fundamental to how Spring adds behaviour (transactions, security, caching) transparently.

### Trade-off
CGLIB proxies require classes to be non-`final` and have a no-args constructor. This is why Spring beans annotated with `@Configuration` cannot be `final`.

---

## 10. Builder Pattern

### Where
`Account.builder()`, `AccountEntity.builder()`, `ErrorResponse` (fluent setter API generated from OpenAPI).

### What it does
Separates the construction of a complex object from its representation, allowing the same construction process to create different representations.

```java
Account.builder()
    .userId("user-001")
    .balance(new BigDecimal("100.00"))
    .build();
```

### Why used
Avoids telescoping constructors (`new Account(userId, balance, null, null, null)`). Makes construction sites readable and resistant to argument-order mistakes.

### Trade-off
Lombok's `@Builder` does not enforce required fields at compile time — `Account.builder().build()` compiles and runs fine, producing a `null`-filled object. For domain objects with invariants, a custom constructor with validation is safer.

---

## Summary

| Pattern | Where | Problem it solves |
|---|---|---|
| Adapter | `AccountAdapter`, `RedisIdempotencyStore` | Translates Redis API into domain SPI |
| Port & Adapter | Entire module structure | Isolates domain from infrastructure |
| Template Method | `IdempotencyGuard.resolve()` | Reusable cache-check/execute/store skeleton |
| Strategy | `MatchCalculationService` | Swappable tier-calculation algorithm |
| Factory Method | `ClockConfig @Bean` | Environment-specific object creation |
| Null Object | `Optional<Account>` | Eliminates null returns from repository |
| Value Object | `PaymentResult` record | Immutable, equality-by-value result container |
| Facade | `ProcessPaymentService` | Single entry point hiding orchestration complexity |
| Proxy | Spring CGLIB / AOP | Cross-cutting concerns without modifying targets |
| Builder | Lombok `@Builder` | Readable, order-independent object construction |
