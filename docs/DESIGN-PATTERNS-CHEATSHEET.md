# Design Patterns - Quick Reference & Cheat Sheet

## 1️⃣ Visual Pattern Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                    Customer Care API                            │
│                   Design Pattern Stack                          │
└─────────────────────────────────────────────────────────────────┘

                    HEXAGONAL ARCHITECTURE
                        (Framework)
                             │
        ┌────────────────────┼────────────────────┐
        │                    │                    │
    DRIVING ADAPTERS    DOMAIN LOGIC       DRIVEN ADAPTERS
    (Controllers)       (Pure Business)     (Persistence)
        │                    │                    │
    ┌───────┐           ┌────────┐          ┌────────┐
    │ REST  │─Ports─────│        │─SPIs────│ Redis  │
    │ API   │           │Domain  │         │Adapter │
    └───────┘           │        │         └────────┘
        │               └────────┘              │
        │                    │                  │
    DEPENDENCY INJECTION   FACTORY         REPOSITORY
    STRATEGY PATTERN      PATTERN         + ADAPTER PATTERN
        │                    │                  │
    ┌───────┐       ┌──────────┐       ┌──────────────┐
    │MAPPER │       │  Clock   │       │  MapStruct   │
    │(DTOs) │       │ (Fixed/  │       │   Mapper     │
    └───────┘       │ System)  │       └──────────────┘
                    └──────────┘
        │                │                      │
    ADVICE PATTERN   TEMPLATE METHOD       VALUE OBJECTS
        │             PATTERN                   │
    ┌─────────┐    ┌──────────┐          ┌──────────┐
    │ Global  │    │Idempotency│         │Payment   │
    │Exception│    │Guard      │         │Result    │
    │Handler  │    │(Strategy) │         │(Record)  │
    └─────────┘    └──────────┘         └──────────┘
```

---

## 2️⃣ Pattern Distribution

```
┌──────────────────────┬─────────────┬──────────┬────────────┐
│ Pattern              │ Complexity  │ In Code  │ Importance │
├──────────────────────┼─────────────┼──────────┼────────────┤
│ Hexagonal Arch       │ ⭐⭐⭐       │ Everywhere│ ⭐⭐⭐⭐⭐  │
│ Dependency Inj       │ ⭐⭐        │ Everywhere│ ⭐⭐⭐⭐⭐  │
│ Repository           │ ⭐⭐⭐       │ Infra    │ ⭐⭐⭐⭐   │
│ Adapter              │ ⭐⭐⭐       │ Infra    │ ⭐⭐⭐⭐   │
│ Mapper               │ ⭐          │ App      │ ⭐⭐⭐⭐   │
│ Exception Advice     │ ⭐⭐        │ App      │ ⭐⭐⭐⭐   │
│ Strategy             │ ⭐⭐        │ App      │ ⭐⭐⭐    │
│ Template Method      │ ⭐⭐        │ App      │ ⭐⭐⭐    │
│ Factory              │ ⭐⭐        │ Bootstrap│ ⭐⭐⭐    │
│ Builder              │ ⭐          │ Domain   │ ⭐⭐     │
│ Optional             │ ⭐          │ Everywhere│ ⭐⭐⭐⭐  │
│ Value Object         │ ⭐          │ Domain   │ ⭐⭐⭐   │
└──────────────────────┴─────────────┴──────────┴────────────┘
```

---

## 3️⃣ Patterns in Action - Life of a Payment Request

```
1. REQUEST ARRIVES
   └─ @RestController receives HTTP
   
2. CONTROLLER (Driving Adapter + Builder)
   └─ PaymentController receives request
   └─ Builds OneTimePaymentRequest (no manual construction)
   
3. DEPENDENCY INJECTION
   └─ Spring injects PaymentService, IdempotencyGuard, etc.
   └─ Constructor injection makes dependencies explicit
   
4. STRATEGY PATTERN (IdempotencyGuard)
   ├─ Check idempotency key
   ├─ If cached: return cached response
   └─ If not: execute supplier (strategy)
   
5. SERVICE EXECUTION (Domain Logic)
   └─ ProcessPaymentService.process()
   ├─ Factory: Get Clock (fixed for test, real for prod)
   ├─ Strategy: MatchCalculationService (1%, 3%, or 5%)
   ├─ Strategy: DueDateCalculationService (+ 15 days, shift weekend)
   ├─ Repository: Find account via AccountSpi
   └─ Repository: Save account
   
6. ADAPTER LAYER (Driven Adapter)
   ├─ AccountAdapter implements AccountSpi
   ├─ Maps Redis ↔ Domain (AccountEntityMapper)
   └─ Stores/retrieves from Redis
   
7. RESPONSE MAPPING (Mapper Pattern)
   ├─ PaymentResult → OneTimePaymentResponse (MapStruct)
   └─ Optional fields automatically handled
   
8. EXCEPTION HANDLING (Advice Pattern)
   ├─ If AccountNotFoundException: GlobalExceptionHandler
   ├─ Maps to 404 Not Found
   ├─ Returns standardized ErrorResponse
   └─ Stack trace never leaked to client
   
9. RESPONSE SENT
   └─ HTTP 200 OK with OneTimePaymentResponse JSON
```

---

## 4️⃣ When to Use Each Pattern

### 🟢 USE IN THIS PROJECT

| Pattern | Location | Why Used |
|---------|----------|----------|
| Hexagonal | Everywhere | Isolate business logic from frameworks |
| Mapping | DTO conversions | Type-safe, zero-reflection conversions |
| Repository | Data access | Abstract persistence, enable swaps |
| Adapter | Infra | Bridge domain SPIs to external systems |
| Exception Advice | Global handler | Centralize error translation |
| Dependency Injection | Every service | Loose coupling, testability |

### 🟡 USED SELECTIVELY

| Pattern | Location | Why Selective |
|---------|----------|---------------|
| Strategy | IdempotencyGuard | Only for varying algorithms |
| Factory | ClockConfig | Only for conditional creation |
| Template Method | IdempotencyGuard | Only when algorithm skeleton is stable |
| Builder | Domain models | Only for objects with many fields |

### 🔴 AVOID IN THIS PROJECT

| Pattern | Why Avoided | Alternative |
|---------|------------|-------------|
| Abstract Factory | Overkill for Clock (Spring handles it) | Spring Conditional beans |
| Singleton | Spring manages lifecycle | Use @Component |
| Facade | No simplification needed | Direct service calls |
| Proxy | Spring AOP handles it | @Aspect, @Transactional |
| Observer/Event | Simple app doesn't need pub-sub | Direct method calls |

---

## 5️⃣ Pros/Cons Quick Scorecard

```
Pattern               Pros                      Cons                      Score
─────────────────────────────────────────────────────────────────────────────
Hexagonal            ⭐⭐⭐⭐⭐              ⭐⭐⭐               ⭐⭐⭐⭐
Repository           ⭐⭐⭐⭐               ⭐⭐⭐               ⭐⭐⭐⭐
Strategy             ⭐⭐⭐⭐               ⭐⭐                ⭐⭐⭐⭐
Mapper               ⭐⭐⭐⭐               ⭐⭐                ⭐⭐⭐⭐
Adapter              ⭐⭐⭐⭐               ⭐⭐                ⭐⭐⭐⭐
Exception Advice     ⭐⭐⭐⭐               ⭐⭐                ⭐⭐⭐⭐
Factory              ⭐⭐⭐                ⭐                 ⭐⭐⭐⭐
Template Method      ⭐⭐⭐                ⭐⭐                ⭐⭐⭐
Dependency Injection ⭐⭐⭐⭐               ⭐⭐⭐               ⭐⭐⭐
Builder              ⭐⭐⭐                ⭐⭐                ⭐⭐⭐⭐
Optional             ⭐⭐⭐⭐               ⭐                 ⭐⭐⭐⭐
Value Object         ⭐⭐⭐⭐               ⭐                 ⭐⭐⭐⭐
```

---

## 6️⃣ Key Files by Pattern

```
Hexagonal Architecture
  domain/                          (core, no framework imports)
  infra/                           (driven adapters)
  app/                             (driving adapters)
  bootstrap/                       (wires together)

Repository Pattern
  domain/spi/AccountSpi.java       (interface)
  infra/adapter/AccountAdapter     (implementation)
  infra/repository/AccountRedisRepository (Spring Data)

Mapper Pattern
  app/mapper/PaymentResponseMapper
  infra/redis/mapper/AccountEntityMapper

Strategy Pattern
  app/idempotency/IdempotencyGuard (Supplier<T> strategy)
  domain/service/MatchCalculationService (tier strategy)

Adapter Pattern
  infra/redis/adapter/AccountAdapter
  infra/redis/adapter/RedisIdempotencyStore

Factory Pattern
  bootstrap/config/ClockConfig.java (Clock.fixed vs Clock.system)

Exception Advice
  app/handler/GlobalExceptionHandler.java (@RestControllerAdvice)

Template Method
  app/idempotency/IdempotencyGuard.resolve() (cache-check/exec/store)

Dependency Injection
  Everywhere (@RequiredArgsConstructor on @Service, @Component)

Builder
  domain/model/Account (@Builder from Lombok)
  domain/payment/PaymentResult (record - implicit builder)

Optional
  domain/spi/AccountSpi (Optional<Account> return type)
  infra/redis/adapter/RedisIdempotencyStore (Optional<T> find)

Value Object
  domain/payment/PaymentResult (record)
```

---

## 7️⃣ Pattern Combinations (Synergies)

### Strong Combinations

```
1. HEXAGONAL + REPOSITORY + ADAPTER
   └─ Domain defines SPI → Infra provides adapter → Easy swaps
   
2. DEPENDENCY INJECTION + FACTORY
   └─ Spring injects fixed Clock in tests, real Clock in prod
   
3. MAPPER + DEPENDENCY INJECTION
   └─ Mappers injected into services, not created manually
   
4. ADAPTER + TEMPLATE METHOD
   └─ IdempotencyGuard as adapter wrapping real service (strategy)
   
5. EXCEPTION ADVICE + VALUE OBJECT (Record)
   └─ ErrorResponse (value object) created by advice, sent as JSON
```

### Patterns That Don't Cloud Each Other

```
✅ Strategy + Template Method = Flexible algorithm with structure
✅ Adapter + Repository = Data abstraction layers
✅ Builder + Dependency Injection = Clean construction + injection
✅ Optional + Factory = Fluent null-safety with conditional creation
```

---

## 8️⃣ Design Principles Score

```
Principle                How Well Applied    Evidence
─────────────────────────────────────────────────────────────
Single Responsibility    ⭐⭐⭐⭐⭐      Each service has one job
Open/Closed             ⭐⭐⭐⭐⭐      New adapters don't modify domain
Liskov Substitution     ⭐⭐⭐⭐        Adapters swap seamlessly
Interface Segregation   ⭐⭐⭐⭐⭐      Focused interfaces (AccountSpi)
Dependency Inversion    ⭐⭐⭐⭐⭐      Depend on abstractions (SPIs)
DRY                     ⭐⭐⭐⭐⭐      No duplication across layers
KISS                    ⭐⭐⭐⭐        Complexity justified by benefits
YAGNI                   ⭐⭐⭐⭐        Only patterns that solve real needs
```

---

## 9️⃣ Trade-offs Summary

### Complexity vs Clarity

```
Low Complexity                          High Complexity
(Simple CRUD monolith)                  (Hexagonal + Adapters)
        │                                      │
        │                                      │
    ❌ Hard to test      ✅ Easy to test      ✓
    ❌ Tight coupling    ✅ Loose coupling     ✓
    ✅ Fast to build     ❌ Slower initially   ✓
    ✅ Few files         ❌ More files         ✓
    ❌ Hard to modify    ✅ Easy to modify     ✓
    ❌ Hard to scale     ✅ Easy to scale      ✓
        │                                      │
    APPROPRIATE FOR:               APPROPRIATE FOR:
    Prototypes, MVPs               Production systems,
    Learning projects              Enterprise apps,
    Simple scripts                 Long-lived codebases
```

### This Project's Position

```
Complexity Spectrum:
Simple MVC ◄──────────── Customer Care API ────────────► DDD with Event Sourcing
           Monolith                ↑                          Microservices
                          Production-Ready
                          (not over-engineered)
                          ✅ Right balance
```

---

## 🔟 Cheat Sheet: When to Add Patterns

### ✅ Add Pattern if:
- [ ] Solves a real pain point (not hypothetical)
- [ ] Multiple places have similar logic (DRY)
- [ ] Team can understand and maintain it
- [ ] Benefits > Complexity added
- [ ] Used consistently throughout

### ❌ Don't Add Pattern if:
- [ ] "Just because it's cool"
- [ ] Only used in one place
- [ ] Adds complexity for edge cases
- [ ] Team doesn't understand it
- [ ] Can solve with simpler approach

---

## Summary

### This Codebase Demonstrates:

✅ **Pragmatic Pattern Usage**: Patterns where they help, not everywhere  
✅ **Layered Architecture**: Clear responsibilities, easy reasoning  
✅ **Testability**: Patterns enable fast unit tests without Spring  
✅ **Maintainability**: Patterns reduce duplication and coupling  
✅ **Extensibility**: Patterns make adding features safe  
✅ **Production-Ready**: Error handling, logging, concurrency aware  

### NOT Over-Engineered:

❌ No Abstract Factory (simple Clock)  
❌ No Observer (no pub-sub needed)  
❌ No Visitor (no complex traversals)  
❌ No State Machine (no complex flows)  
❌ No Singleton (Spring manages lifecycle)  

### For Your Project:

> **Use as reference for:**
> - When to apply patterns (real problems, not theory)
> - How to combine patterns synergistically
> - Trade-offs (complexity vs benefits)
> - Knowing when to say "No" to patterns

---

**Remember**: A pattern is a solution to a recurring problem. If you don't have the problem, you don't need the pattern. This codebase shows excellent judgment in that regard.

