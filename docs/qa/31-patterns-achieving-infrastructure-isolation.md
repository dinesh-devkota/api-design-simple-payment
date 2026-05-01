# Q&A 31 — Other Patterns That Achieve Infrastructure Isolation

> Companion to `01-hexagonal-architecture.md`. Hexagonal Architecture guarantees the infra layer can be swapped without touching the domain. This file covers every other pattern and principle that achieves the same guarantee — and explains why they all reduce to the same root idea.

---

## Q1: What is the core claim of Hexagonal Architecture that we are trying to match?

**A:** From file 01:
> *"Swapping the persistence store (e.g. Redis → PostgreSQL) requires changing only the `infra` module adapter, not the business logic."*

The guarantee is: **the domain has zero knowledge of what is outside it**. It defines what it needs (via interfaces/ports), and the outside world satisfies those needs. Any pattern that enforces this boundary achieves the same property.

---

## Q2: What is Clean Architecture and how does it compare to Hexagonal?

**A:** Clean Architecture (Robert C. Martin, *"Uncle Bob"*) organises code into concentric circles:

```
+------------------------------------------+
|  Frameworks & Drivers (Redis, Spring MVC) |
|  +------------------------------------+   |
|  |  Interface Adapters (Controllers,  |   |
|  |  Repositories, Presenters)         |   |
|  |  +------------------------------+  |   |
|  |  |  Application Business Rules  |  |   |
|  |  |  (Use Cases)                 |  |   |
|  |  |  +------------------------+  |  |   |
|  |  |  |  Enterprise Business   |  |  |   |
|  |  |  |  Rules (Entities)      |  |  |   |
|  |  |  +------------------------+  |  |   |
|  |  +------------------------------+  |   |
|  +------------------------------------+   |
+------------------------------------------+
```

**The Dependency Rule:** Source code dependencies can only point *inward*. Nothing in an inner circle knows anything about an outer circle.

**Mapped to this project:**

| Clean Architecture | This project |
|---|---|
| Enterprise Business Rules | `Account`, `PaymentResult`, domain exceptions |
| Application Business Rules | `ProcessPaymentService`, `MatchCalculationServiceImpl` |
| Interface Adapters | `PaymentController`, `AccountAdapter`, `RedisIdempotencyStore` |
| Frameworks & Drivers | Spring Boot, Redis, Lettuce, Jackson |

**Difference from Hexagonal:** Hexagonal uses the metaphor of a hexagon with "primary" (driving) and "secondary" (driven) sides. Clean Architecture uses concentric circles with an inward dependency rule. The guarantee is identical — the naming and diagram differ.

---

## Q3: What is Onion Architecture?

**A:** Onion Architecture (Jeffrey Palermo, 2008) is a third naming of the same structural idea:

```
        [ Infrastructure ]
      [ Application Services ]
    [ Domain Services          ]
  [ Domain Model                 ]
```

Layers:
1. **Domain Model** — entities and value objects (`Account`, `PaymentResult`).
2. **Domain Services** — business logic that operates on domain objects (`MatchCalculationService`).
3. **Application Services** — orchestrate domain services, define use cases (`ProcessPaymentService`).
4. **Infrastructure** — all I/O: databases, HTTP, messaging (`AccountAdapter`, `RedisIdempotencyStore`).

**Key rule:** All dependencies point inward toward the domain model. Infrastructure depends on Application Services through interfaces defined in the inner layers.

**Same guarantee:** Swapping the infrastructure layer (outer ring) never touches the domain model (inner ring).

---

## Q4: How does the Repository Pattern achieve the same isolation?

**A:** The Repository Pattern (Eric Evans, *Domain-Driven Design*) abstracts data access behind an interface that the domain owns:

```java
// Domain defines what it needs — owns this interface
public interface AccountSpi {
    Optional<Account> findById(String userId);
    void save(Account account);
}

// Infrastructure provides the implementation
public class AccountAdapter implements AccountSpi {
    private final AccountRedisRepository repo;
    // ... Redis-specific code here
}
```

This is exactly what this project does. `AccountSpi` is a Repository port. The domain's `ProcessPaymentService` calls `accountSpi.findById()` — it has zero knowledge that Redis exists.

**Swapping to PostgreSQL:** Write `AccountJpaAdapter implements AccountSpi`. Wire it in `bootstrap`. Done. The domain is untouched.

**Difference from Hexagonal:** The Repository Pattern is a *class-level* pattern focused specifically on data access. Hexagonal Architecture is an *architectural* pattern applied to the entire system — ports exist for every external concern (HTTP in, Redis out, email out, etc.), not just data access.

---

## Q5: How does the Strategy Pattern achieve the same isolation?

**A:** The Strategy Pattern defines a family of interchangeable algorithms behind a common interface. The context class holds a reference to the interface, not a concrete implementation.

```java
// The "strategy" interface — domain owns it
public interface IdempotencyStoreSpi {
    Optional<String> find(String key);
    void store(String key, String value);
}

// Concrete strategies — in infra
public class RedisIdempotencyStore implements IdempotencyStoreSpi { ... }
public class InMemoryIdempotencyStore implements IdempotencyStoreSpi { ... }  // for tests
public class JdbcIdempotencyStore implements IdempotencyStoreSpi { ... }      // for SQL migration
```

`IdempotencyGuard` holds an `IdempotencyStoreSpi` reference — it is the "context" in Strategy terms. Swapping the implementation requires no change to `IdempotencyGuard` or any domain class.

**Difference from Repository:** Strategy is about swapping *behaviour/algorithms*. Repository is about swapping *data access*. Both use the same structural mechanism (interface + implementation), but their intent differs.

---

## Q6: How does the Adapter Pattern (GoF) fit in?

**A:** The Adapter Pattern converts the interface of a class into another interface that clients expect. It lets two incompatible interfaces work together.

In this project:

```
Domain interface (what the domain expects):
    AccountSpi.findById(String userId) → Optional<Account>

Third-party reality (what Spring Data Redis gives you):
    AccountRedisRepository.findById(String id) → Optional<AccountEntity>

Adapter (bridges the gap):
    AccountAdapter.findById(String userId) {
        return repo.findById(userId)
            .map(mapper::toDomain);   // AccountEntity → Account conversion
    }
```

`AccountAdapter` adapts the Spring Data Redis repository (incompatible interface) to the `AccountSpi` interface (what the domain expects).

**Difference:** The GoF Adapter pattern is about *interface translation* — making two incompatible interfaces compatible. Hexagonal Architecture uses adapters as a structural concept — the entire infra layer *is* a collection of adapters. The Adapter pattern is the mechanism; Hexagonal Architecture is the strategy that decides where to use it.

---

## Q7: How does the Bridge Pattern compare?

**A:** The Bridge Pattern (GoF) decouples an *abstraction* from its *implementation* so both can vary independently:

```
Abstraction (Payment processing):
    PaymentProcessor
        process(userId, amount)

Implementation interface:
    PaymentStorage
        findAccount(userId)
        saveAccount(account)

Concrete implementations:
    RedisPaymentStorage implements PaymentStorage
    PostgresPaymentStorage implements PaymentStorage
```

The abstraction (`PaymentProcessor`) holds a reference to the implementation interface (`PaymentStorage`). Both can evolve independently:
- Add `ScheduledPaymentProcessor` without changing storage.
- Add `PostgresPaymentStorage` without changing processor.

**vs Hexagonal:** Bridge is a structural pattern for a *single class pair*. Hexagonal Architecture applies the same decoupling *system-wide* with explicit "ports" as the bridge interfaces.

**In practice:** The Bridge pattern describes the micro-level structure that hexagonal architecture uses at the macro level.

---

## Q8: What is the Dependency Inversion Principle and why do all these patterns reduce to it?

**A:** DIP (the D in SOLID):

> *"High-level modules must not depend on low-level modules. Both should depend on abstractions. Abstractions should not depend on details. Details should depend on abstractions."*

Applied to this project:

```
WITHOUT DIP (bad):
ProcessPaymentService → AccountRedisRepository (concrete Redis class)
                      ↑ domain knows Redis exists — coupling

WITH DIP (current design):
ProcessPaymentService → AccountSpi (interface, owned by domain)
AccountAdapter        → AccountSpi (implements it)
AccountAdapter        → AccountRedisRepository (uses Redis)
```

The domain defines the interface it needs (`AccountSpi`). Infrastructure provides the implementation. The dependency arrow points *toward the domain* — not outward toward Redis.

**Why every pattern reduces to DIP:**

| Pattern | DIP expression |
|---|---|
| Hexagonal Architecture | Ports are the abstractions; adapters are the details |
| Clean Architecture | Inner circle defines interfaces; outer circle implements |
| Onion Architecture | Inner rings define interfaces; outer rings depend inward |
| Repository | Domain defines repository interface; infra implements |
| Strategy | Context depends on strategy interface; concrete strategies are details |
| Adapter (GoF) | Client depends on target interface; adapter and adaptee are details |
| Bridge | Abstraction depends on implementation interface; concrete implementors are details |

**Every single pattern is DIP at a different scope or with a different motivating metaphor.**

---

## Q9: When would you choose Repository over Hexagonal Architecture?

**A:**

| Choose... | When... |
|---|---|
| **Repository Pattern only** | Small application, one or two persistence concerns, team unfamiliar with hexagonal. You get data-access isolation without the overhead of full module separation. |
| **Hexagonal Architecture** | Multiple I/O concerns (HTTP in, Redis out, email out, queue out). Team wants explicit ports for every external dependency. Future-proofing against infra changes. |
| **Clean Architecture** | Enterprise scale, multiple teams, strict layer ownership. The explicit "use case" layer helps when business rules are complex and numerous. |
| **Strategy Pattern only** | You need runtime-swappable behaviour (e.g., different pricing strategies per customer tier). Data-access swapping is a secondary concern. |

**For this project (one endpoint, one Redis store):** Repository Pattern alone would have been sufficient for data-access isolation. Hexagonal Architecture was chosen because it signals intent to grow — the `ProcessPaymentUseCase` interface, the SPI pattern, and the module boundaries all prepare for Iteration 2 (see `12-over-engineering-vs-intentional-design.md`).

---

## Q10: Summary — the pattern family tree

**A:**

```
Dependency Inversion Principle (root idea)
│
├── Architectural patterns (system-wide DIP)
│   ├── Hexagonal Architecture (Ports & Adapters)
│   ├── Clean Architecture (concentric circles)
│   └── Onion Architecture (domain at centre)
│
└── Design patterns (class-level DIP)
    ├── Repository Pattern (data access isolation)
    ├── Strategy Pattern (behaviour/algorithm isolation)
    ├── Adapter Pattern (interface translation)
    └── Bridge Pattern (abstraction ↔ implementation decoupling)
```

**One-line rule:** If a pattern requires the domain/high-level module to depend on an *interface it owns* rather than a *concrete class someone else owns*, it achieves infrastructure isolation. They all do exactly this — they differ only in scope, naming, and the specific problem they were invented to solve.
