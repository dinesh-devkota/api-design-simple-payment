# Design Patterns in Customer Care API

## Overview

This document identifies and analyzes all design patterns used in the Customer Care API codebase, with detailed pros and cons for each approach.

---

## Table of Contents

1. [Hexagonal Architecture (Ports & Adapters)](#1-hexagonal-architecture-ports--adapters)
2. [Repository Pattern](#2-repository-pattern)
3. [Strategy Pattern](#3-strategy-pattern)
4. [Mapper Pattern (MapStruct)](#4-mapper-pattern-mapstruct)
5. [Adapter Pattern](#5-adapter-pattern)
6. [Factory Pattern](#6-factory-pattern)
7. [Template Method Pattern](#7-template-method-pattern)
8. [Centralized Exception Handler (Advice Pattern)](#8-centralized-exception-handler-advice-pattern)
9. [Builder Pattern](#9-builder-pattern)
10. [Dependency Injection](#10-dependency-injection)
11. [Optional Pattern](#11-optional-pattern)
12. [Value Object Pattern](#12-value-object-pattern)

---

## 1. Hexagonal Architecture (Ports & Adapters)

### Definition
**Hexagonal Architecture** (also called Ports & Adapters) is an architectural pattern that isolates the core business logic (domain) from external concerns (UI, databases, frameworks) through well-defined boundaries.

### Usage in Codebase

**Structure:**
```
domain/          ← Core business logic (no framework imports)
├── Defines: ProcessPaymentUseCase (primary port)
├── Defines: AccountSpi, IdempotencyStoreSpi (secondary ports)
└── No knowledge of: Spring, Redis, HTTP

app/             ← Driving adapters (controllers, mappers)
├── Calls: ProcessPaymentUseCase
├── Knows: HTTP, Spring Web
└── No knowledge of: Redis, persistence details

infra/           ← Driven adapters (persistence implementation)
├── Implements: AccountSpi, IdempotencyStoreSpi
├── Knows: Redis, Spring Data
└── No knowledge of: HTTP, DTOs

bootstrap/       ← Wires everything together
└── Knows: All modules
```

### Dependency Rules
```
bootstrap → app, infra, domain
app       → domain
infra     → domain
domain    → nothing (internal)
```

### Pros ✅

| Advantage | Explanation | Example |
|-----------|-------------|---------|
| **Framework Independence** | Domain logic doesn't depend on Spring, Hibernate, or any framework | `Account.java` has no `@Entity`, `@Component`, or persistence annotations |
| **Testability** | Domain easily tested with mocks; no Spring context needed | `ProcessPaymentServiceTest` uses pure Mockito, no `@SpringBootTest` |
| **Pluggable Persistence** | Swap Redis → Oracle by changing only the infra adapter | `AccountAdapter` implements `AccountSpi`; domain untouched |
| **Clear Separation** | Each layer has one responsibility | Domain = business rules, App = HTTP translation, Infra = persistence |
| **Enterprise Scalability** | Scales to multiple adapters (REST API, gRPC, CLI) sharing same domain | Future: add PaymentGrpcAdapter without touching domain |
| **Reduces Boilerplate** | No ORM annotations cluttering domain models | `Account` is a clean 25-line POJO |
| **Business-First Design** | Domain models map to business concepts, not database tables | `PaymentResult` represents a business outcome, not a query result |

### Cons ❌

| Disadvantage | Explanation | Mitigation |
|-------------|-------------|-----------|
| **More Modules** | Four modules instead of three increases complexity | Clear documentation of rules + IDE support makes it manageable |
| **Boilerplate Code** | More interface definitions and adapter classes | MapStruct eliminates mapper boilerplate; ports are minimal |
| **Slower to Prototype** | Takes longer than a simple MVC monolith | Worth the investment for production systems |
| **Learning Curve** | Team must understand ports/adapters concept | Training + documentation (see ARCHITECTURE.md) |
| **More Files** | More classes to navigate during development | IDE refactoring tools (Find Usages, Go to Implementation) help |
| **No Built-in Navigation** | IDE can't auto-wire dependencies like in MVC | Constructor injection makes dependencies explicit (good!) |

### When to Use It

✅ **Use Hexagonal when:**
- Building production systems that will evolve
- Need to isolate business logic for reuse
- Plan for persistence migration (e.g., Redis → Oracle)
- Team values testability and clean architecture

❌ **Don't use when:**
- Building throw-away prototypes (<2 weeks)
- Simple CRUD app with no business logic
- Very small team with high Sprint velocity priority

---

## 2. Repository Pattern

### Definition
**Repository Pattern** abstracts data access logic behind an interface, treating collections as in-memory repositories rather than database queries.

### Usage in Codebase

**Layers:**
```java
// 1. Domain SPI (Secondary Port)
public interface AccountSpi {
    Optional<Account> findById(String userId);
    Account save(Account account);
}

// 2. Spring Data Interface
public interface AccountRedisRepository extends CrudRepository<AccountEntity, String> {
}

// 3. Adapter (Implementation)
@Component
public class AccountAdapter implements AccountSpi {
    private final AccountRedisRepository repo;
    
    @Override
    public Optional<Account> findById(String userId) {
        return repo.findById(userId).map(accountEntityMapper::toDomain);
    }
    
    @Override
    public Account save(Account account) {
        AccountEntity entity = accountEntityMapper.toEntity(account);
        return accountEntityMapper.toDomain(repo.save(entity));
    }
}
```

### Pros ✅

| Advantage | Explanation | Example |
|-----------|-------------|---------|
| **Query Abstraction** | Business logic doesn't know HOW data is fetched | Service calls `accountSpi.findById()`, doesn't care about SQL or Redis commands |
| **Persistence Agnostic** | Easy to swap implementations | Replace `AccountRedisRepository` with `AccountJpaRepository` without changing service |
| **Testable** | Mock the repository in unit tests | `ProcessPaymentServiceTest` passes a mock `AccountSpi` |
| **Collections Metaphor** | Feels like querying an in-memory list | `findById()` feels natural, not like a DB query |
| **Single Access Point** | All queries for an entity go through one place | All `Account` lookups via `AccountSpi` |
| **Encapsulates Logic** | Complex queries hidden in repository | Future: add `findByBalanceRange()` without touching domain |

### Cons ❌

| Disadvantage | Explanation | Mitigation |
|-------------|-------------|-----------|
| **Extra Layer** | Domain + Service + Repository + Adapter = 4 classes | Spring Data reduces this; interface + adapter only = 2 |
| **Not True Repositories** | Spring Data generates code, repos aren't truly your implementation | Acceptable trade-off for 80% reduction in boilerplate |
| **Lost DB Features** | Can't easily use complex SQL, transactions, or window functions | Move to JDBC for complex queries, or raw SQL queries |
| **N+1 Query Problem** | Eager loading buried in repository implementation | Use Spring Data `@EntityGraph` for JPA or explicit queries |

### When to Use It

✅ **Use Repository when:**
- Persistence likely to change
- Multiple entity types with independent query logic
- Want to hide database details from business logic

❌ **Don't use when:**
- Simple project with no persistence changes planned
- Heavy analytics/reporting (raw SQL needed)
- Using complex database features extensively

---

## 3. Strategy Pattern

### Definition
**Strategy Pattern** defines a family of algorithms, encapsulates each one, and makes them interchangeable. Client can choose which strategy to use at runtime.

### Usage in Codebase

**In `IdempotencyGuard`:**
```java
public <T> T resolve(String key, Class<T> type, Supplier<T> supplier) {
    if (key == null || key.isBlank()) {
        return supplier.get();  // Strategy: execute directly
    }
    
    Optional<T> cached = idempotencyStore.find(key, type);
    if (cached.isPresent()) {
        return cached.get();  // Strategy: return from cache
    }
    
    T result = supplier.get();  // Strategy: execute and cache
    idempotencyStore.store(key, result);
    return result;
}
```

The `Supplier<T>` is the **strategy**:
- `processPaymentService::process` wrapped in a supplier = one strategy
- Future: `refundService::refund` = another strategy
- IdempotencyGuard doesn't care which; it works with any supplier

**In `MatchCalculationService`:**
```java
public BigDecimal calculateMatchAmount(BigDecimal paymentAmount) {
    int pct = getMatchPercentage(paymentAmount);  // Strategy selection
    return paymentAmount
        .multiply(BigDecimal.valueOf(pct))
        .divide(HUNDRED, 2, RoundingMode.HALF_UP);
}
```

The **tier logic** is a strategy:
- Payment < $10 → 1% strategy
- Payment < $50 → 3% strategy
- Payment >= $50 → 5% strategy

### Pros ✅

| Advantage | Explanation | Example |
|-----------|-------------|---------|
| **Reusable Logic** | Same caching pattern used for any operation | Future: apply to refunds, adjustments, chargebacks |
| **Easy to Test** | Each strategy tested independently | Mock `Supplier<PaymentResponse>` in tests |
| **Runtime Flexibility** | Choose algorithm at runtime, not compile time | If-else tier selection vs hardcoded percentages |
| **Follows Open/Closed** | Open for extension (new strategies), closed for modification | Add new tier without changing existing code |
| **Decouples Caller** | Caller doesn't care about strategy implementation | Controller calls `idempotencyGuard.resolve()`, not caching logic directly |

### Cons ❌

| Disadvantage | Explanation | Mitigation |
|-------------|-------------|-----------|
| **Extra Classes** | Each strategy = new class in larger systems | For simple cases, inline if-else is clearer |
| **Indirection** | Harder to trace execution than direct calls | Good IDE support for "Go to Implementation" |
| **Overkill for Simple Logic** | Using Supplier for trivial operations adds overhead | Ask: "Is this logic likely to change?" If no, inline |

### When to Use It

✅ **Use Strategy when:**
- Logic varies based on input/configuration
- Multiple related algorithms
- Different algorithms need switching at runtime
- Want to test each algorithm independently

❌ **Don't use when:**
- Only one way to do something
- Algorithm never changes
- Logic is trivial (< 3 lines)

---

## 4. Mapper Pattern (MapStruct)

### Definition
**Mapper Pattern** converts objects from one type to another (e.g., Entity → DTO, Domain → Response) in a type-safe, testable way.

### Usage in Codebase

**Three Mappers:**

1. **PaymentResponseMapper** (Domain → REST DTO)
```java
@Mapper(componentModel = "spring")
public interface PaymentResponseMapper {
    OneTimePaymentResponse toResponse(PaymentResult result);
}
```

2. **AccountEntityMapper** (Persistence ↔ Domain)
```java
@Mapper(componentModel = "spring")
public interface AccountEntityMapper {
    Account toDomain(AccountEntity entity);
    AccountEntity toEntity(Account account);
}
```

3. **Generated at compile time** (invisible to code)
```java
@Component
public class PaymentResponseMapperImpl implements PaymentResponseMapper {
    public OneTimePaymentResponse toResponse(PaymentResult result) {
        // mapstruct generates this implementation
        OneTimePaymentResponse response = new OneTimePaymentResponse();
        response.setUserId(result.userId());
        response.setNewBalance(result.newBalance());
        // ... fields match by name
        return response;
    }
}
```

### Pros ✅

| Advantage | Explanation | Example |
|-----------|-------------|---------|
| **Zero Reflection** | Compile-time code generation = fast at runtime | No `BeanUtils.copyProperties()` overhead |
| **Type Safe** | Compile-time checking catches mismatches | IDE shows error if field doesn't exist |
| **Explicit** | Clear what fields are mapped | Not magical like `ModelMapper` |
| **Zero Config** | Auto-maps fields with matching names | `PaymentResult.newBalance` → `OneTimePaymentResponse.newBalance` |
| **Annotation-Driven** | Drop `@Mapping` for custom logic | `@Mapping(target = "id", source = "uuid")` |
| **Null Handling** | Configurable null-checking | Prevents `NullPointerException` on optional fields |
| **Spring Integration** | Automatically registered as `@Component` | Dependency injection just works |
| **IDE Support** | Full refactoring support | Rename field → mapper automatically updates |

### Cons ❌

| Disadvantage | Explanation | Mitigation |
|-------------|-------------|-----------|
| **Compile-Time Dependency** | Requires annotation processor (annotation-processing plugin) | Standard Maven setup; no runtime dependency |
| **Generated Code Hidden** | You don't see what it generates | Use IDE to "Show Generate File" or check target/ folder |
| **Limited for Complex Logic** | If mapping needs conditionals, MapStruct can't do it all | Add custom method: `@Mapping(qualifiedByName = "customMethod")` |
| **Learning Curve** | Not as obvious as `new ResponseMapper()` | Minimal; just `@Mapper` and `toResponse()` |
| **Debugging** | Stack traces point to generated code | Generated code is readable; easy to understand |

### When to Use It

✅ **Use Mappers when:**
- Converting between layers (Entity → DTO, Domain → Response)
- Need type safety and compile-time checking
- Mapping happens frequently
- Want zero runtime reflection overhead

❌ **Don't use when:**
- One-time conversion (just inline manual conversion)
- Field names don't match (no benefit over manual)
- Complex conditional logic (use a service instead)

---

## 5. Adapter Pattern

### Definition
**Adapter Pattern** (Structural) converts the interface of a class into another interface clients expect. It allows incompatible interfaces to work together.

### Usage in Codebase

**Two Adapters implementing Domain SPIs:**

**1. AccountAdapter** (Redis → Account SPI)
```java
@Component
public class AccountAdapter implements AccountSpi {  // Converts Redis to domain contract
    private final AccountRedisRepository repo;       // Redis interface
    private final AccountEntityMapper mapper;
    
    @Override
    public Optional<Account> findById(String userId) {
        // Redis returns AccountEntity; adapter converts to Account (domain)
        return repo.findById(userId).map(mapper::toDomain);
    }
}
```

**2. RedisIdempotencyStore** (Redis → IdempotencyStoreSpi)
```java
@Component
public class RedisIdempotencyStore implements IdempotencyStoreSpi {  // Converts Redis to domain contract
    private final StringRedisTemplate redisTemplate;  // Redis interface
    
    @Override
    public <T> Optional<T> find(String key, Class<T> type) {
        String json = redisTemplate.opsForValue().get(PREFIX + key);
        return json == null ? Optional.empty() 
                           : Optional.of(objectMapper.readValue(json, type));
    }
}
```

**The Adaptation:**
- **Redis interface**: `StringRedisTemplate.opsForValue().get()`, manual key construction, JSON parsing
- **Domain interface**: Clean Optional-returning methods, type-generic
- **Adapter's job**: Bridge the gap

### Pros ✅

| Advantage | Explanation | Example |
|-----------|-------------|---------|
| **Decouples Domains** | Incompatible interfaces work together without one knowing about the other | Domain doesn't know about Redis; Redis doesn't know about domain |
| **Reusability** | Domain logic reusable with different implementations | Same `ProcessPaymentService` works with Redis today, Oracle tomorrow |
| **Plugin Architecture** | Easy to add new adapters | Create `AccountJpaAdapter` implementing same `AccountSpi` |
| **Testing** | Mock implementations for testing | `ProcessPaymentServiceTest` uses mock adapter, not real Redis |
| **Isolation** | Infrastructure details hidden from business logic | No `@RedisHash`, `redisTemplate`, TTL logic in domain |

### Cons ❌

| Disadvantage | Explanation | Mitigation |
|-------------|-------------|-----------|
| **Extra Layer** | Every integration needs an adapter = more code | But less code than scattered Spring Data throughout domain |
| **Indirection** | Method calls go through adapter before hitting real service | Negligible performance cost (microseconds) |
| **Requires SPI** | Must define interface first (design overhead) | Forces upfront thinking (good!) |

### When to Use It

✅ **Use Adapters when:**
- Integrating external systems
- Want to swap implementations
- Multiple implementations needed (REST, gRPC, CLI)

---

## 6. Factory Pattern

### Definition
**Factory Pattern** creates objects without exposing the creation logic to the client. Client requests an object; factory decides which concrete type to instantiate.

### Usage in Codebase

**ClockConfig as Factory:**
```java
@Configuration
public class ClockConfig {
    
    @Bean
    @ConditionalOnProperty(name = "app.fixed-date")
    public Clock fixedClock(@Value("${app.fixed-date}") String fixedDate) {
        // Factory creates fixed clock when property is set
        return Clock.fixed(
            LocalDate.parse(fixedDate).atStartOfDay(zone).toInstant(),
            zone);
    }
    
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock systemClock() {
        // Factory creates system clock as fallback
        return Clock.systemDefaultZone();
    }
}
```

**How it works:**
1. Client code: `LocalDate.now(clock)` (doesn't care which clock)
2. Factory: Decides based on `@ConditionalOnProperty`
3. Result: Fixed clock for testing, system clock for production

### Pros ✅

| Advantage | Explanation | Example |
|-----------|-------------|---------|
| **Decouples Creation** | Client doesn't care which Clock implementation | Works with fixed clock for tests, system clock in prod |
| **Centralized Decision** | All instantiation logic in one place | ClockConfig only place that knows about fixed vs system |
| **Runtime Selection** | Decide which implementation at runtime | No code changes; just different app.properties |
| **Easy Testing** | Inject mock/fixed implementation | Unit tests get Clock.fixed(); production gets Clock.systemDefaultZone() |
| **Follows Interface Segregation** | Client depends on Clock interface, not implementations | Can add new Clock types without changing clients |

### Cons ❌

| Disadvantage | Explanation | Mitigation |
|-------------|-------------|-----------|
| **Extra Configuration** | Spring conditions and beans add complexity | Worth it for flexible production code |
| **Hidden Creation** | Where Clock comes from not obvious | IDE "Go to Definition" shows ClockConfig |

### When to Use It

✅ **Use Factory when:**
- Multiple implementations of same interface
- Creation logic complex
- Decision based on runtime configuration

---

## 7. Template Method Pattern

### Definition
**Template Method Pattern** defines skeleton of algorithm in base class, lets subclasses override specific steps.

### Usage in Codebase

**IdempotencyGuard.resolve() as Template Method:**
```java
public <T> T resolve(String key, Class<T> type, Supplier<T> supplier) {
    // Step 1: Check if key is valid
    if (key == null || key.isBlank()) {
        return supplier.get();  // Step - skip caching
    }
    
    // Step 2: Try cache
    Optional<T> cached = idempotencyStore.find(key, type);
    if (cached.isPresent()) {
        log.info("Idempotency cache hit: key={}", key);  // Step - log hit
        return cached.get();
    }
    
    // Step 3: Execute (the varying part)
    T result = supplier.get();  // THE STRATEGY — varies based on what client passes
    
    // Step 4: Store
    idempotencyStore.store(key, result);
    return result;
}
```

**The Steps:**
1. Validate key (fixed)
2. Check cache (fixed)
3. Execute supplier (varying) ← Customization point
4. Store result (fixed)

The **template** is Steps 1, 2, 4. The **strategy** is Step 3 (what the supplier does).

### Pros ✅

| Advantage | Explanation | Example |
|-----------|-------------|---------|
| **Reusable Structure** | Same cache-check pattern for any operation | Works for payments, refunds, adjustments |
| **Consistent Behavior** | All idempotent operations follow same flow | Logging, TTL, error handling identical |
| **Single Point of Change** | Fix bug in caching logic → fixes all uses | Change log.info to log.debug everywhere automatically |
| **Extensible** | Add new steps without touching subclasses | Future: add metrics collection step |

### Cons ❌

| Disadvantage | Explanation | Mitigation |
|-------------|-------------|-----------|
| **Rigid Structure** | If step order needs to change, have to modify class | Design carefully upfront |
| **Limited Customization** | Can only vary what's in supplier; structure fixed | Acceptable trade-off for consistency |

### When to Use It

✅ **Use Template Method when:**
- Same algorithm used multiple places
- Some steps fixed, some vary
- Want consistent behavior across variations

---

## 8. Centralized Exception Handler (Advice Pattern)

### Definition
**Advice Pattern** (or Interceptor Pattern) centralizes cross-cutting concerns (like exception handling) in one place rather than scattered throughout code.

### Usage in Codebase

**GlobalExceptionHandler with `@RestControllerAdvice`:**
```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    
    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAccountNotFound(AccountNotFoundException ex) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage(), null);
    }
    
    @ExceptionHandler(InvalidPaymentAmountException.class)
    public ResponseEntity<ErrorResponse> handleInvalidPaymentAmount(InvalidPaymentAmountException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), null);
    }
    
    // ... more handlers
}
```

**Without this pattern (in controller):**
```java
// ANTI-PATTERN
public ResponseEntity<PaymentResponse> payment(...) {
    try {
        return ResponseEntity.ok(service.process(...));
    } catch (AccountNotFoundException ex) {
        return ResponseEntity.status(404)
            .body(new ErrorResponse().status(404).message(ex.getMessage()));
    } catch (InvalidPaymentAmountException ex) {
        return ResponseEntity.status(400)
            .body(new ErrorResponse().status(400).message(ex.getMessage()));
    }
    // Repeated in every endpoint  ❌
}
```

### Pros ✅

| Advantage | Explanation | Example |
|-----------|-------------|---------|
| **DRY (Don't Repeat Yourself)** | Exception handling in one place, not per-controller | All 404s mapped same way everywhere |
| **Consistent Responses** | Every error has same structure | All errors use `ErrorResponse` format |
| **Centralized Logic** | Change error format → change one class | Add timestamp to all errors; edit one method |
| **Separation of Concerns** | Controllers focus on happy path | Exception translation is separate responsibility |
| **Easy Testing** | Mock exception throwing; handler tested separately | `GlobalExceptionHandlerTest` tests all mappings |
| **Security** | Stack traces never leak to client | `handleGeneric()` only logs server-side |
| **Extensibility** | Add new exception type → one new handler | Future: add custom header to all 400s |

### Cons ❌

| Disadvantage | Explanation | Mitigation |
|-------------|-------------|-----------|
| **Hidden from Callers** | When reading controller, not obvious what happens if exception thrown | Good IDE support shows annotations |
| **Limited to Web Layer** | Only works for Spring Web exceptions | Acceptable; services don't throw HTTP errors |

### When to Use It

✅ **Use Advice when:**
- Cross-cutting concern (logging, security, monitoring)
- Behavior consistent across application
- Want to centralize decision-making

---

## 9. Builder Pattern

### Definition
**Builder Pattern** provides step-by-step object construction as alternative to large constructors or telescoping constructors.

### Usage in Codebase

**Lombok `@Builder` on Domain Models:**
```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Account {
    private String userId;
    private BigDecimal balance;
}
```

**Usage in tests:**
```java
Account account = Account.builder()
    .userId("user-1")
    .balance(new BigDecimal("100.00"))
    .build();
```

**Benefits over constructor:**
```java
// Constructor approach (hard to read)
Account account = new Account("user-1", new BigDecimal("100.00"));

// Builder approach (self-documenting)
Account account = Account.builder()
    .userId("user-1")
    .balance(new BigDecimal("100.00"))
    .build();
```

### Pros ✅

| Advantage | Explanation | Example |
|-----------|-------------|---------|
| **Readability** | Field names clear in fluent builder | `.userId("x").balance(y)` vs constructor with positional args |
| **Optional Fields** | Don't need multiple constructors | Add field without breaking existing creations |
| **Immutability** | Can be used with `final` fields | Lombok applies to all fields |
| **Documentation** | Method names document what each field is | IDE autocomplete shows field options |
| **Default Values** | Easy to provide sensible defaults | Only set non-defaults |

### Cons ❌

| Disadvantage | Explanation | Mitigation |
|-------------|-------------|-----------|
| **More Verbose** | `.build()` adds boilerplate | Trade-off for clarity; acceptable |
| **Runtime Overhead** | Creates builder object before entity | Negligible; builder often optimized away |
| **Lombok Magic** | Generates code you don't see | IDE support for code inspection |

### When to Use It

✅ **Use Builder when:**
- Multiple optional fields
- Creating objects frequently in tests
- Want fluent API
- Making immutable objects

---

## 10. Dependency Injection

### Definition
**Dependency Injection** provides dependencies to objects rather than objects creating them, improving testability and flexibility.

### Usage in Codebase

**Constructor Injection (preferred):**
```java
@Service
@RequiredArgsConstructor  // Lombok generates constructor
public class ProcessPaymentService implements ProcessPaymentUseCase {
    
    private final AccountSpi accountSpi;
    private final MatchCalculationService matchCalculationService;
    private final DueDateCalculationService dueDateCalculationService;
    private final Clock clock;
    
    // Lombok generates:
    // public ProcessPaymentService(AccountSpi accountSpi, 
    //                             MatchCalculationService matchCalc,
    //                             DueDateCalculationService dateSvc, 
    //                             Clock clock)
}
```

**vs Field Injection (anti-pattern):**
```java
@Service
public class ProcessPaymentService {
    
    @Autowired
    private AccountSpi accountSpi;  // ❌ Hard to test, hidden dependency
}
```

### Pros ✅

| Advantage | Explanation | Example |
|-----------|-------------|---------|
| **Constructor Immutability** | Fields are `final`; can't be changed after construction | No accidental modification after creation |
| **Explicit Dependencies** | All dependencies listed in constructor signature | Reading constructor shows everything object needs |
| **Testability** | Easy to pass mocks in tests | `new ProcessPaymentService(mockSpi, mockMatch, mockDue, mockClock)` |
| **Compile-Time Safety** | If dependency missing, won't compile | IDE catches missing beans immediately |
| **Circular Dependency Detection** | Spring catches cycles at startup, not runtime | Fails fast instead of `NullPointerException` at runtime |
| **Single Responsibility** | Object doesn't care how to create dependencies | Decouples creation from usage |

### Cons ❌

| Disadvantage | Explanation | Mitigation |
|-------------|-------------|-----------|
| **More Boilerplate** | Every class needs constructor (without Lombok) | Use Lombok `@RequiredArgsConstructor` |
| **Larger Constructor** | Many dependencies = long parameter list | Sign of needing to break class into smaller pieces (SRP) |
| **Lazy Initialization** | Can't inject null and initialize later | Use Optional<T> if dependency is optional |

### When to Use It

✅ **Always use Constructor Injection when:**
- Using Spring or similar DI framework
- Want testable code
- Need explicit dependencies

---

## 11. Optional Pattern

### Definition
**Optional Pattern** represents optional values without using `null`, eliminating `NullPointerException` and making intent explicit.

### Usage in Codebase

**Finding Account:**
```java
// In ProcessPaymentService
Account account = accountSpi.findById(userId)
    .orElseThrow(() -> new AccountNotFoundException(
        "Account not found for userId: " + userId));

// vs null handling (error-prone)
Account account = accountSpi.findById(userId);
if (account == null) {  // ❌ Easy to forget
    throw new AccountNotFoundException(...);
}
```

**Finding Cached Response:**
```java
Optional<T> cached = idempotencyStore.find(key, type);
if (cached.isPresent()) {
    return cached.get();
}
```

### Pros ✅

| Advantage | Explanation | Example |
|-----------|-------------|---------|
| **Null-Safety** | Compiler/IDE force handling of "no value" case | Can't accidentally call `.getName()` on null |
| **Intent Clarity** | Optional clearly means "may not exist" | vs unclear contract from `findById()` returning null |
| **Functional API** | `.map()`, `.filter()`, `.orElse()` elegant | Optional<Account>.map(::getBalance) |
| **No Null Checks** | Functional approach cleaner than if-statements | Avoids nested null checks / "null hell" |
| **Explicit Handling** | Must decide what to do if absent | Good for error handling; throws exception or returns default |

### Cons ❌

| Disadvantage | Explanation | Mitigation |
|-------------|-------------|-----------|
| **Overhead** | Optional object created even if present | Negligible for most use cases |
| **Not Serializable** | Can't serialize Optional in JSON | Don't use in entities/DAOs; only in APIs |
| **Learning Curve** | Functional methods less familiar than if-statements | Brief learning; becomes natural quickly |
| **Two Ways to Do Same Thing** | `.map().orElse()` vs if-else confuse new developers | Code review to ensure consistency |

### When to Use It

✅ **Use Optional when:**
- A value might not exist (query results, lookups)
- Want to avoid null checks
- Using modern Java (8+)

❌ **Don't use Optional when:**
- Field/variable always has a value
- Serializing to JSON (use nullable field in DTO instead)

---

## 12. Value Object Pattern

### Definition
**Value Object** is an immutable object that represents a value (like Money or a Date Range) with no identity — only the values matter.

### Usage in Codebase

**PaymentResult as Value Object:**
```java
public record PaymentResult(
    String userId,
    BigDecimal previousBalance,
    BigDecimal paymentAmount,
    int matchPercentage,
    BigDecimal matchAmount,
    BigDecimal newBalance,
    LocalDate nextPaymentDueDate,
    LocalDate paymentDate) {
}
```

**vs Entity approach:**
```java
// ❌ Entity has identity
@Entity
@Table("payment_results")
public class PaymentEntity {
    @Id
    private UUID id;  // ← Identity (database assigned)
    private String userId;
    // ... mutability risk, tracking overhead
}
```

### Pros ✅

| Advantage | Explanation | Example |
|-----------|-------------|---------|
| **Immutability** | Can't be changed after creation | No accidental modifications during processing |
| **Equality by Value** | Two records with same values are equal | `new PaymentResult(...) == new PaymentResult(...)` |
| **No Identity** | Value object doesn't need database ID | Lightweight, no ORM overhead |
| **Thread-Safe** | Immutable = automatically thread-safe | No synchronization needed |
| **Semantic Clarity** | PaymentResult is a value, not persistent entity | Makes business intent clear |
| **Java Records** | Minimal boilerplate with Java 16+ record syntax | Auto-generates `equals()`, `hashCode()`, `toString()` |

### Cons ❌

| Disadvantage | Explanation | Mitigation |
|-------------|-------------|-----------|
| **Create-and-Discard** | Can't modify in place; must create new object | Acceptable; typically lightweight objects |
| **Not Persistent** | Value objects not directly stored in DB | Store as part of entity or serialize to JSON |
| **Memory Usage** | If object large and many created, memory overhead | Use for small objects; entities for large ones |

### When to Use It

✅ **Use Value Objects when:**
- Representing a value (money, date, percentage)
- Value doesn't change (immutable)
- No identity needed (interchangeable with same-valued objects)
- Lightweight (few large fields)

---

## Summary Comparison Table

| Pattern | Use Case | Complexity | Learning Curve |
|---------|----------|-----------|-----------------|
| **Hexagonal** | Modular enterprise apps | High | Medium |
| **Repository** | Data access abstraction | Medium | Low |
| **Strategy** | Algorithm selection | Low-Medium | Low |
| **Mapper** | Format conversion | Low | Low |
| **Adapter** | Integration/compatibility | Medium | Medium |
| **Factory** | Object creation | Low-Medium | Low |
| **Template Method** | Algorithm skeleton | Medium | Low |
| **Advice** | Cross-cutting concerns | Low | Low |
| **Builder** | Object construction | Low | Low |
| **Dependency Injection** | Loose coupling | Medium | Medium |
| **Optional** | Null safety | Low | Low |
| **Value Object** | Immutable values | Low | Low |

---

## Design Principles Behind These Patterns

### SOLID Principles Applied

| Principle | How This Code Follows It |
|-----------|--------------------------|
| **S - Single Responsibility** | `AccountSpi` (persistence), `MatchCalcService` (math), `DueCalcService` (date) separate concerns |
| **O - Open/Closed** | Open for extension (new adapters); closed for modification (domain untouched) |
| **L - Liskov Substitution** | `RedisIdempotencyStore` swappable with any `IdempotencyStoreSpi` impl |
| **I - Interface Segregation** | `ProcessPaymentUseCase` has one method; clients don't depend on unused methods |
| **D - Dependency Inversion** | Depends on `AccountSpi` (abstraction); not `AccountRedisRepository` (concrete) |

### DRY (Don't Repeat Yourself)

| Area | How It's Applied |
|------|------------------|
| **Exception handling** | `GlobalExceptionHandler` one place, not per-endpoint |
| **Mapping** | `AccountEntityMapper` shared, not duplicated |
| **Caching logic** | `IdempotencyGuard` reused for all idempotent ops |
| **Tier calculation** | `MatchCalculationService` single source of truth |
| **Date calculation** | `DueDateCalculationService` single source of truth |

---

## Conclusion

This codebase demonstrates **mature design patterns** applied thoughtfully:

✅ **Used when they solve real problems** (not over-engineered)  
✅ **Not stacked unnecessarily** (e.g., Hexagonal for simple 1-endpoint app would be overkill)  
✅ **Documented trade-offs** (see ARCHITECTURE.md for rationale)  
✅ **Team-appropriate** (patterns taught in doc/code comments)  

**Best Takeaway**: Patterns are tools, not rules. This codebase chooses patterns that enable:
- Testability (mocks, fast unit tests)
- Flexibility (swap Redis for Oracle)
- Maintenance (clear separation, single source of truth)
- Production readiness (error handling, logging, concurrency awareness)

Rather than patterns for their own sake.

