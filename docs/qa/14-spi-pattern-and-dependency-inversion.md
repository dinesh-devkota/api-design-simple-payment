# Q&A 14 — SPI Pattern, Dependency Inversion, and Interface Design

> What SPIs are, how they differ from APIs, why this codebase uses them, and how DI wires it all together.

---

## Q1: What does SPI stand for and how does it differ from an API?

**A:**

| Term | Stands for | Who defines it | Who implements it | Who calls it |
|---|---|---|---|---|
| **API** | Application Programming Interface | The *provider* of a service | The provider | The *consumer* of that service |
| **SPI** | Service Provider Interface | The *consumer* of a service | A *provider* (adapter) | The consumer, via the interface |

In plain terms:
- An **API** is a contract the provider exposes for others to call (e.g., `ProcessPaymentUseCase` — the domain defines it and provides an implementation; the controller calls it).
- An **SPI** is a contract the consumer defines that it *needs* implemented by someone else (e.g., `AccountSpi` — the domain defines what it needs from a persistence store; the `infra` module provides the Redis implementation).

This distinction is the mechanism behind **Dependency Inversion** — high-level modules define SPIs, low-level modules implement them, so the dependency arrow points toward the high-level module.

---

## Q2: What are the two SPIs in this project and what do they declare?

**A:**

### `AccountSpi` — persistence contract

```java
// In: domain module
public interface AccountSpi {
    Optional<Account> findById(String userId);
    Account save(Account account);
}
```

The domain declares: *"I need something that can fetch and save accounts. I don't care if it's Redis, Postgres, an in-memory map, or a file. Here's the contract."*

`AccountAdapter` in `infra` provides the Redis implementation.

---

### `IdempotencyStoreSpi` — caching contract

```java
// In: domain module
public interface IdempotencyStoreSpi {
    <T> Optional<T> find(String key, Class<T> type);
    void store(String key, Object value);
}
```

The domain declares: *"I need a key-value store that can cache responses. I don't care if it's Redis, Memcached, an in-memory `ConcurrentHashMap`, or a database table."*

`RedisIdempotencyStore` in `infra` provides the Redis implementation.

---

## Q3: Where exactly do `ProcessPaymentUseCase` and `AccountSpi` fit — API or SPI?

**A:**

```
                   ┌─────────────────────────────┐
                   │         domain module        │
                   │                              │
   [PaymentApi] ──►│  ProcessPaymentUseCase  (API)│
   (primary port)  │  ProcessPaymentService       │
                   │                              │
                   │  AccountSpi            (SPI)│◄── [AccountAdapter]
                   │  IdempotencyStoreSpi   (SPI)│◄── [RedisIdempotencyStore]
                   │  (secondary ports)           │     (infra module)
                   └─────────────────────────────┘
```

- `ProcessPaymentUseCase` = **primary port (API)**: defined by the domain, called by the `app` module (the controller drives the domain).
- `AccountSpi` = **secondary port (SPI)**: defined by the domain, implemented by the `infra` module (the domain drives the infrastructure).

The naming convention `*UseCase` for primary ports and `*Spi` for secondary ports is a readable convention that immediately communicates direction.

---

## Q4: What is Dependency Inversion and how does this codebase apply it?

**A:** Dependency Inversion Principle (DIP, the "D" in SOLID):

> *High-level modules should not depend on low-level modules. Both should depend on abstractions.*

Without DIP (wrong):
```
domain.ProcessPaymentService → infra.AccountAdapter → Redis
```
`domain` imports `infra` → business logic depends on Redis → changing Redis requires changing the domain.

With DIP (this codebase):
```
infra.AccountAdapter → domain.AccountSpi ← domain.ProcessPaymentService
```
Both `domain` and `infra` depend on the `AccountSpi` abstraction defined in `domain`. Maven enforces this: `domain/pom.xml` has no dependency on `infra`.

---

## Q5: How does Spring wire the SPI interface to its implementation at runtime?

**A:** Spring's IoC container handles this automatically at startup:

1. `@ComponentScan` in `@SpringBootApplication` (starting at `com.customercare`) discovers all `@Component`, `@Service`, and `@Repository` beans across all modules.
2. `AccountAdapter` is annotated `@Component` and implements `AccountSpi`.
3. `ProcessPaymentService` declares `private final AccountSpi accountSpi` in its constructor.
4. Spring sees one bean implementing `AccountSpi` → injects `AccountAdapter`.

If two beans implemented `AccountSpi`, Spring would throw `NoUniqueBeanDefinitionException`. You'd resolve it with `@Primary` or `@Qualifier`.

**Why this is powerful:** `ProcessPaymentService` never imports `AccountAdapter`. It doesn't know Redis exists. Swap the implementation to JPA and the service class is untouched — only the `@Component` that implements `AccountSpi` changes.

---

## Q6: How does the test suite exploit the SPI pattern?

**A:** In `ProcessPaymentServiceTest`:

```java
@Mock
private AccountSpi accountSpi;   // Mockito creates a dynamic proxy implementing AccountSpi

@BeforeEach
void setUp() {
    useCase = new ProcessPaymentService(
            accountSpi,           // inject the mock — no Redis needed
            new MatchCalculationServiceImpl(),
            new DueDateCalculationServiceImpl(),
            FIXED_CLOCK);
}
```

Because `ProcessPaymentService` depends on the `AccountSpi` *interface* (not `AccountAdapter` or `RedisTemplate`), Mockito can substitute a mock implementation with zero infrastructure. This is the testability payoff of the SPI pattern.

---

## Q7: Why is `ProcessPaymentUseCase` an interface when there is only one implementation?

**A:** Three reasons:

**1. Testability at call sites.**
Any class that injects `ProcessPaymentUseCase` can be tested with a mock of the interface. If the controller injected `ProcessPaymentService` directly, you'd need to instantiate the full service (with all its dependencies) in controller tests.

**2. The interface IS the contract.**
The interface documents what the use-case guarantees: accepts `userId` and `paymentAmount`, returns `PaymentResult`, throws specific exceptions. The implementation is a detail. Consumers of the domain should read the interface, not the service class.

**3. Architecture enforcement.**
If `PaymentController` imports `ProcessPaymentUseCase` (an interface in `domain`), there is no transitive dependency on any implementation detail. If the controller imported `ProcessPaymentService` directly, refactoring the service class would ripple into the controller.

> *"Program to an interface, not an implementation"* — GoF Design Patterns, 1994.

---

## Q8: What are the advantages and disadvantages of interface-per-service?

### Advantages
| Advantage | Detail |
|---|---|
| Swappable implementations | Swap Redis → JPA by writing a new `@Component` that implements `AccountSpi` |
| Mockable in tests | Any injection point can be replaced with a Mockito mock |
| Clear contracts | Interfaces document what a component promises, not how it does it |
| Enables multiple implementations | `@Primary`/`@Qualifier` lets you have prod and stub implementations in the same context |

### Disadvantages
| Disadvantage | Detail |
|---|---|
| One-to-one interfaces are often unnecessary | If there will only ever be one implementation, the interface adds navigation overhead |
| Interface proliferation | Every service gets an interface, every SPI gets an interface — many files for small codebases |
| IDE navigation friction | "Go to implementation" requires one extra click vs. direct class references |

---

## Q9: What is the difference between Java's built-in SPI mechanism (`ServiceLoader`) and Spring's DI-based SPI pattern?

**A:**

| Mechanism | Java `ServiceLoader` | Spring Dependency Injection |
|---|---|---|
| How | `META-INF/services/` files list implementations | `@Component` + `@ComponentScan` auto-discovers |
| Usage context | CLI tools, modular JARs, plugin systems | Spring Boot applications |
| Runtime discovery | Lazy, on-demand loading | Eager, at application startup |
| Type safety | Cast required after `ServiceLoader.load()` | Fully typed via interface injection |
| In this project | Not used | Used exclusively |

Java's `ServiceLoader` is ideal for plugin architectures where implementations are loaded from external JARs at runtime (e.g., JDBC drivers, logging implementations). Spring DI is better suited for fixed-composition applications where all modules are known at build time.

---

## Q10: If a second payment method (e.g., bank draft) were added, how would the SPI pattern accommodate it?

**A:** The existing `AccountSpi` would not change. The new feature would either:

**Option A: Reuse the same `AccountSpi`** — if bank drafts operate on the same `Account` entity (same balance, same userId), a new `ProcessBankDraftUseCase` with a new `ProcessBankDraftService` would depend on the same `AccountSpi`. No infrastructure change needed.

**Option B: Add a new SPI** — if bank drafts need a new data source (e.g., bank routing/account numbers), a new `BankAccountSpi` would be defined in the domain, with a new adapter in `infra`. The `Account` domain model, `ProcessPaymentService`, and `PaymentController` are untouched.

This is the hexagonal architecture's key promise: **adding features extends the system, it does not modify the core.**
