What # Q&A 01 — Hexagonal Architecture (Ports & Adapters)

> Covers the four-module layout, the "why", trade-offs, and improvement opportunities.

---

## Q1: What is Hexagonal Architecture and why does this project use it?

**A:** Hexagonal Architecture (also called *Ports & Adapters*) organises code so that **business logic is isolated from delivery mechanisms and infrastructure concerns**. The "hexagon" is the domain; everything outside (REST, Redis, Docker) is a plug-in adapter.

This project uses it because:
- The payment rules are the most valuable, fragile, and frequently-changing code. Isolating them means they can evolve without touching HTTP or Redis code.
- The domain can be unit-tested without starting Spring, Redis, or any container.
- Swapping the persistence store (e.g. Redis → PostgreSQL) requires changing only the `infra` module adapter, not the business logic.

---

## Q2: What are the four Maven modules and what does each one own?

**A:**

| Module      | Layer              | What it contains                                                              |
|-------------|--------------------|-------------------------------------------------------------------------------|
| `domain`    | Core hexagon       | `Account`, `PaymentResult`, `ProcessPaymentService`, `MatchCalculationService`, `DueDateCalculationService`, SPI interfaces (`AccountSpi`, `IdempotencyStoreSpi`), domain exceptions |
| `app`       | Primary adapter    | `PaymentController`, `HelloController`, `PaymentResponseMapper`, `IdempotencyGuard`, `GlobalExceptionHandler` |
| `infra`     | Secondary adapter  | `AccountAdapter`, `RedisIdempotencyStore`, `AccountEntity`, `AccountEntityMapper`, `RedisConfig`, `AccountRedisRepository` |
| `bootstrap` | Wiring module      | `CustomerCareApplication` (Spring Boot entry point), `ClockConfig`, all YAML configs, integration tests |

---

## Q3: What is a "port" and what is an "adapter" in this codebase?

**A:**

- **Primary port** — an API the domain *exposes* for callers to drive it. `ProcessPaymentUseCase` is the primary port; `PaymentController` is the primary adapter (it drives the use-case via HTTP).
- **Secondary port (SPI)** — an interface the domain *defines* but does not implement, representing something it needs from the outside world. `AccountSpi` and `IdempotencyStoreSpi` are secondary ports.
- **Secondary adapters** — `AccountAdapter` and `RedisIdempotencyStore` implement those SPIs using Redis. Replacing Redis means writing new adapters — the domain interfaces stay unchanged.

---

## Q4: Why is the `domain` module not allowed to import from `infra` or `app`?

**A:** The dependency direction enforces the architecture. The dependency arrow in Hexagonal Architecture always points **inward toward the domain**:

```
app  →  domain  ←  infra
         ↑
      bootstrap
```

If `domain` imported `infra`, it would create a circular dependency and couple business logic to Redis. Maven enforces this naturally: `domain/pom.xml` declares no sibling-module dependencies at all.

---

## Q5: Why split into four modules instead of one Spring Boot monolith?

**A:** A single module is simpler to bootstrap, but this project separates them for these reasons:

**Advantages of the multi-module split:**
- **Compile-time enforcement** of layer boundaries — Maven cannot compile `domain` if you accidentally import `infra` classes.
- **Independent deployability** (future) — each module can be versioned or extracted to a library.
- **Faster incremental builds** — Maven only recompiles modules whose transitive sources changed.
- **Clarity** — a new developer immediately knows where to look for business logic vs REST vs persistence.

**Disadvantages / costs:**
- Higher initial setup overhead (four `pom.xml` files, inter-module dependency declarations).
- More complex build commands (`-pl bootstrap`).
- Overkill for a very small codebase; the structure pays off as the service grows.

---

## Q6: What would the dependency graph look like if a second feature (e.g., recurring payments) were added?

**A:** The second feature would follow the same structure:
- A new use-case interface and implementation in `domain`.
- A new endpoint in `app` calling that use-case.
- Potentially a new SPI in `domain` if new persistence calls are needed, with a new adapter in `infra`.
- The `bootstrap` module wires everything together.

The key insight: `infra` and `app` grow; `domain` grows in isolation; `bootstrap` remains a thin wiring layer.

---

## Q7: What are the main advantages and disadvantages of this architecture?

**Advantages:**
| Advantage | Detail |
|---|---|
| Testability | Domain services unit-tested with zero infrastructure (no Redis, no HTTP) |
| Replaceability | Any adapter can be swapped without touching business rules |
| Clarity of responsibility | Each class has one reason to change |
| Parallel development | Teams can work on REST and Redis adapters simultaneously |

**Disadvantages / trade-offs:**
| Disadvantage | Detail |
|---|---|
| Over-engineering risk | For a tiny service, four modules and many interfaces add ceremony |
| Indirection | To trace a request you must jump through: Controller → UseCase → SPI → Adapter |
| Mapping overhead | Every layer boundary requires a mapper (MapStruct helps but adds generated code) |
| Onboarding cost | New developers must understand the architecture before contributing |

---

## Q8: What improvements could be made to the architecture?

**A:**
1. **Package-private visibility** — domain services could be made package-private, exposing only the use-case interface publicly. Currently `ProcessPaymentService` is `public`, which allows accidental direct use from `app`.
2. **Architecture tests (ArchUnit)** — add a test that asserts `domain` classes never import `infra` or `app` packages. This gives compile-level enforcement plus a clear failure message.
3. **Explicit module-info.java (JPMS)** — Java 9+ modules would enforce visibility at the JVM level, not just by convention.
4. **Event-driven boundary** — if the service grows, domain events (e.g., `PaymentProcessedEvent`) published to a message bus would decouple `infra` further.
5. **Interface segregation on SPI** — `AccountSpi` currently mixes read and write concerns; splitting into `AccountReadSpi` and `AccountWriteSpi` would follow ISP more strictly.
