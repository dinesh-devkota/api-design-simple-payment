# Complete Java Codebase - Line by Line Breakdown

## Table of Contents

### Application Layer (app/)
1. [PaymentController.java](#paymentcontrollerjava)
2. [HelloController.java](#hellocontrollerjava)
3. [PaymentResponseMapper.java](#paymentresponsemapperjava)
4. [GlobalExceptionHandler.java](#globalexceptionhandlerjava)
5. [IdempotencyGuard.java](#idempotencyguardjava)

### Domain Layer (domain/)
6. [ProcessPaymentUseCase.java](#processpaymentusecasejava)
7. [ProcessPaymentService.java](#processpaymentservicejava)
8. [PaymentResult.java](#paymentresultjava)
9. [Account.java](#accountjava)
10. [AccountSpi.java](#accountspijava)
11. [IdempotencyStoreSpi.java](#idempotencystorespi)
12. [MatchCalculationService.java](#matchcalculationservicejava)
13. [MatchCalculationServiceImpl.java](#matchcalculationserviceimpljava)
14. [DueDateCalculationService.java](#duedatecalculationservicejava)
15. [DueDateCalculationServiceImpl.java](#duedatecalculationserviceimpljava)
16. [AccountNotFoundException.java](#accountnotfoundexceptionjava)
17. [InsufficientBalanceException.java](#insufficientbalanceexceptionjava)
18. [InvalidPaymentAmountException.java](#invalidpaymentamountexceptionjava)

### Infrastructure Layer (infra/)
19. [RedisConfig.java](#redisconfigjava)
20. [AccountAdapter.java](#accountadapterjava)
21. [RedisIdempotencyStore.java](#redisidempotencystorjava)
22. [AccountEntity.java](#accountentityjava)
23. [AccountEntityMapper.java](#accountentitymapperjava)
24. [AccountRedisRepository.java](#accountredisrepository)

### Bootstrap Layer (bootstrap/)
25. [CustomerCareApplication.java](#customercareapplicationjava)
26. [ClockConfig.java](#clockconfigjava)

---

## Application Layer (app/)

### PaymentController.java

**Location:** `app/src/main/java/com/customercare/app/rest/PaymentController.java`

**Purpose:** Thin HTTP adapter for one-time payment operations. Implements contract-first API from OpenAPI specification.

#### Lines 1-6: Package and Imports

```java
package com.customercare.app.rest;

import com.customercare.api.PaymentApi;
import com.customercare.app.idempotency.IdempotencyGuard;
import com.customercare.app.mapper.PaymentResponseMapper;
import com.customercare.domain.payment.ProcessPaymentUseCase;
```

- **Package**: Organizes controller within the REST adapter layer
- **PaymentApi**: Generated interface from OpenAPI spec defining the HTTP contract
- **IdempotencyGuard**: Component encapsulating idempotency cache-check/execute/store pattern
- **PaymentResponseMapper**: MapStruct mapper converting domain result to REST DTO
- **ProcessPaymentUseCase**: Domain use-case interface for payment processing

#### Lines 8-16: Additional Imports and Class Declaration

```java
import com.customercare.dto.OneTimePaymentRequest;
import com.customercare.dto.OneTimePaymentResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin REST adapter for one-time payment operations.
 *
 * <p>Implements the contract-first {@link PaymentApi} interface generated from
 * {@code openapi.yaml}.  All Swagger/OpenAPI annotations, request mappings, and
 * response codes live on the generated interface.
 *
 * <p>This controller has a single responsibility: translate HTTP ↔ domain.
 * Idempotency cache logic lives in {@link IdempotencyGuard}; business rules
 * live in {@link ProcessPaymentUseCase}.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PaymentController implements PaymentApi {
```

**Key Annotations & Concepts:**
- `@Slf4j`: Auto-generates `log` field via Lombok
- `@RestController`: Registers as Spring MVC REST endpoint handler
- `@RequiredArgsConstructor`: Generates constructor for all `final` fields
- `implements PaymentApi`: Implements generated contract interface
- `MDC`: Mapped Diagnostic Context for adding userId to all logs from this request

#### Line 35: Static Constant

```java
private static final String MDC_USER_ID = "userId";
```
- Key used to store userId in MDC (Mapped Diagnostic Context)
- Allows userId to be automatically included in all logs for this request

#### Lines 37-39: Dependency Injection

```java
private final ProcessPaymentUseCase processPaymentUseCase;
private final PaymentResponseMapper paymentResponseMapper;
private final IdempotencyGuard      idempotencyGuard;
```

- All dependencies are `final` (immutable, thread-safe)
- Auto-injected via generated constructor from `@RequiredArgsConstructor`
- Clear separation: UseCase (business), Mapper (conversion), Guard (idempotency)

#### Lines 41-72: Main Handler Method

```java
@Override
public ResponseEntity<OneTimePaymentResponse> oneTimePayment(
        @Valid @RequestBody OneTimePaymentRequest request,
        String idempotencyKey) {

    long start = System.currentTimeMillis();
    MDC.put(MDC_USER_ID, request.getUserId());

    try {
        log.info("POST /one-time-payment received: userId={} amount={} idempotencyKey={}",
                request.getUserId(), request.getPaymentAmount(),
                idempotencyKey != null ? idempotencyKey : "(none)");

        OneTimePaymentResponse response = idempotencyGuard.resolve(
                idempotencyKey,
                OneTimePaymentResponse.class,
                () -> paymentResponseMapper.toResponse(
                        processPaymentUseCase.process(
                                request.getUserId(), request.getPaymentAmount())));

        log.info("POST /one-time-payment completed: userId={} prevBalance={} newBalance={} dueDate={} elapsedMs={}",
                request.getUserId(), response.getPreviousBalance(), response.getNewBalance(),
                response.getNextPaymentDueDate(), System.currentTimeMillis() - start);

        return ResponseEntity.ok(response);

    } finally {
        MDC.remove(MDC_USER_ID);
    }
}
```

**Line-by-Line Explanation:**

**Line 42-44**: Method signature
- `@Override`: Implements interface method
- `@Valid @RequestBody`: Validates request against Bean Validation constraints
- `String idempotencyKey`: Optional idempotency key from HTTP header

**Line 46**: Start timer
```java
long start = System.currentTimeMillis();
```
- Captures current time in milliseconds for response latency logging

**Line 47**: Add userId to MDC
```java
MDC.put(MDC_USER_ID, request.getUserId());
```
- Stores userId in thread-local context
- All log statements in this thread will automatically include userId

**Line 50-52**: Log incoming request
```java
log.info("POST /one-time-payment received: userId={} amount={} idempotencyKey={}",
        request.getUserId(), request.getPaymentAmount(),
        idempotencyKey != null ? idempotencyKey : "(none)");
```
- Logs request details at INFO level
- Useful for request tracing and debugging

**Lines 54-59**: Idempotent payment processing
```java
OneTimePaymentResponse response = idempotencyGuard.resolve(
        idempotencyKey,
        OneTimePaymentResponse.class,
        () -> paymentResponseMapper.toResponse(
                processPaymentUseCase.process(
                        request.getUserId(), request.getPaymentAmount())));
```

**Execution flow:**
1. `idempotencyGuard.resolve()`: Checks if cached response exists
2. If cache hit: returns cached response immediately
3. If cache miss: executes the lambda (supplier):
   - `processPaymentUseCase.process()`: Executes business logic (validate, debit, calculate)
   - `paymentResponseMapper.toResponse()`: Converts domain result to REST DTO
   - Result is cached for future requests with same idempotencyKey

**Lines 61-64**: Log completion
```java
log.info("POST /one-time-payment completed: userId={} prevBalance={} newBalance={} dueDate={} elapsedMs={}",
        request.getUserId(), response.getPreviousBalance(), response.getNewBalance(),
        response.getNextPaymentDueDate(), System.currentTimeMillis() - start);
```
- Logs all payment details and elapsed time
- Enables performance monitoring and audit trail

**Line 66**: Return successful response
```java
return ResponseEntity.ok(response);
```
- Returns 200 OK with payment result

**Lines 69-71**: Finally block - cleanup
```java
} finally {
    MDC.remove(MDC_USER_ID);
}
```
- **Critical**: Removes userId from MDC to prevent context leakage in thread pools
- Guaranteed to execute whether success or exception

**Design Patterns:**
- **Thin Controller**: Only translates HTTP ↔ domain, no business logic
- **Idempotency Pattern**: Guards against duplicate processing
- **MDC Pattern**: Distributes user context through all logs
- **Try-Finally**: Ensures context cleanup

---

### HelloController.java

**Location:** `app/src/main/java/com/customercare/app/rest/HelloController.java`

**Purpose:** Simple health-check/smoke-test endpoint providing API availability verification.

#### Lines 1-6: Package and Imports

```java
package com.customercare.app.rest;

import com.customercare.api.HealthApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
```

**Import Breakdown:**
- `HealthApi`: Generated interface from OpenAPI spec for health endpoint
- `@Slf4j`: Lombok annotation for logging
- `ResponseEntity`: Spring HTTP response wrapper
- `@RestController`: Marks class as REST endpoint handler

#### Lines 8-16: Class Declaration

```java
/**
 * Thin REST adapter for the smoke-test endpoint.
 *
 * <p>Implements the contract-first {@link HealthApi} interface generated from
 * {@code openapi.yaml}.
 */
@Slf4j
@RestController
public class HelloController implements HealthApi {
```

**Notes:**
- Contract-first: Interface generated from OpenAPI spec
- Annotations defined on `HealthApi`, not here
- Single purpose: smoke-test endpoint

#### Lines 18-23: Health Check Method

```java
@Override
public ResponseEntity<String> hello() {
    log.debug("GET /hello");
    return ResponseEntity.ok("Hello from customer-care-api!");
}
```

**Breakdown:**
- `@Override`: Implements `HealthApi.hello()`
- `log.debug()`: Logs at DEBUG level (minimal overhead)
- Returns 200 OK with simple text message
- **Use**: Docker health checks, Kubernetes liveness probes, manual API verification

---

### PaymentResponseMapper.java

**Location:** `app/src/main/java/com/customercare/app/mapper/PaymentResponseMapper.java`

**Purpose:** MapStruct mapper converting domain `PaymentResult` to REST DTO `OneTimePaymentResponse`.

#### Complete File Breakdown

```java
package com.customercare.app.mapper;

import com.customercare.domain.payment.PaymentResult;
import com.customercare.dto.OneTimePaymentResponse;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper that converts the domain's {@link PaymentResult} into the
 * contract DTO {@link OneTimePaymentResponse}.
 *
 * <p>All field names match between source and target, so no {@code @Mapping}
 * annotations are required.  The generated implementation ({@code PaymentResponseMapperImpl})
 * is created at compile time — do <strong>not</strong> edit it by hand.
 */
@Mapper(componentModel = "spring")
public interface PaymentResponseMapper {

    OneTimePaymentResponse toResponse(PaymentResult result);
}
```

**Key Points:**

| Aspect | Explanation |
|--------|------------|
| **MapStruct** | Code generation library that creates mappers at compile time |
| `@Mapper` | Marks interface for MapStruct code generation |
| `componentModel = "spring"` | Generated mapper is registered as Spring bean |
| Method signature | Single method: `PaymentResult` → `OneTimePaymentResponse` |
| Field names | Must match between source and target (auto-mapping works) |
| Generated class | `PaymentResponseMapperImpl` created at compile time |
| **Never edit generated code**: MapStruct will overwrite manual changes |

**Benefits of MapStruct:**
- Zero-reflection mapper (performance)
- Type-safe (compile-time checking)
- Simple field matching with `@Mapping` for complex cases
- Generated Spring bean for dependency injection

---

### GlobalExceptionHandler.java

**Location:** `app/src/main/java/com/customercare/app/handler/GlobalExceptionHandler.java`

**Purpose:** Centralized exception handling that maps domain exceptions to HTTP responses.

*See the dedicated GlobalExceptionHandler-Breakdown.md file for complete line-by-line analysis.*

**Quick Summary:**
- Handles domain exceptions: `AccountNotFoundException` (404), `InsufficientBalanceException` (422), `InvalidPaymentAmountException` (400)
- Handles validation errors: `MethodArgumentNotValidException` (400 with field details)
- Catch-all handler: Any unexpected exception returns 500 with generic message
- All responses use structured `ErrorResponse` DTO
- Logs at appropriate levels (WARN for expected, ERROR for unexpected)

---

### IdempotencyGuard.java

**Location:** `app/src/main/java/com/customercare/app/idempotency/IdempotencyGuard.java`

**Purpose:** Encapsulates idempotency cache-check/execute/store pattern.

*See the dedicated IdempotencyGuard-Breakdown.md file for complete line-by-line analysis.*

**Quick Summary:**
- Checks if response is cached under the idempotency key
- On cache hit: returns cached response immediately
- On cache miss: executes supplier, caches result, returns response
- Null/blank keys bypass caching for non-idempotent operations
- Delegates actual storage to `IdempotencyStoreSpi` (Redis-backed)

---

## Domain Layer (domain/)

### ProcessPaymentUseCase.java

**Location:** `domain/src/main/java/com/customercare/domain/payment/ProcessPaymentUseCase.java`

**Purpose:** Primary port (API) defining the payment processing use-case interface.

#### Complete File Breakdown

```java
package com.customercare.domain.payment;

import java.math.BigDecimal;

/**
 * Primary port (API) — the inbound use-case for processing a one-time payment.
 *
 * <p>The app module drives this port; the domain provides the implementation
 * ({@link ProcessPaymentService}).  Controllers and any other entry-points talk to
 * the domain exclusively through this interface.
 */
public interface ProcessPaymentUseCase {

    /**
     * Processes a one-time payment: validates the amount, debits the account,
     * applies the tier-based match, and calculates the next due date.
     *
     * @param userId        the customer's identifier
     * @param paymentAmount the amount being paid; must be {@code > 0}
     * @return fully computed {@link PaymentResult}
     */
    PaymentResult process(String userId, BigDecimal paymentAmount);
}
```

**Architectural Context:**

**Primary Port** (Inbound Interface):
- Defined by domain
- Implemented by domain (`ProcessPaymentService`)
- Called by application layer (controllers, adapters)
- API contract: what the use-case accepts and returns

**Method Contract:**
- **Input**: `userId` (customer ID) and `paymentAmount` (must be > 0)
- **Output**: `PaymentResult` (immutable value object with all payment details)
- **Exceptions**: May throw domain exceptions (handled by GlobalExceptionHandler)

**Design Principle:**
- Interface belongs in domain (not implementation)
- Controllers accept/return this interface (dependency inversion)
- Makes domain independent of Spring framework

---

### ProcessPaymentService.java

**Location:** `domain/src/main/java/com/customercare/domain/payment/ProcessPaymentService.java`

**Purpose:** Implements the payment processing use-case. Contains core payment logic.

#### Lines 1-29: Package, Imports, and Class Declaration

```java
package com.customercare.domain.payment;

import com.customercare.domain.exception.AccountNotFoundException;
import com.customercare.domain.exception.InsufficientBalanceException;
import com.customercare.domain.exception.InvalidPaymentAmountException;
import com.customercare.domain.model.Account;
import com.customercare.domain.service.DueDateCalculationService;
import com.customercare.domain.service.MatchCalculationService;
import com.customercare.domain.spi.AccountSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;

/**
 * Domain use-case implementation for one-time payment processing.
 *
 * <p>Depends only on domain interfaces ({@link AccountSpi}, {@link MatchCalculationService},
 * {@link DueDateCalculationService}) — it has no knowledge of REST DTOs, Redis, or any
 * infrastructure concern.  Infrastructure wiring is handled by the bootstrap module.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessPaymentService implements ProcessPaymentUseCase {
```

**Key Dependencies:**
- `AccountSpi`: Query/save accounts (persistence abstraction)
- `MatchCalculationService`: Calculate match percentage and amount
- `DueDateCalculationService`: Calculate weekend-adjusted due date
- `Clock`: Injected time source (can be fixed for testing)

**Design Notes:**
- `@Service`: Spring service bean (contains business logic)
- Pure domain logic (no knowledge of HTTP, Redis, Spring DTOs)
- All dependencies are interfaces (depends on abstractions, not concretions)

#### Lines 31-35: Constructor Fields

```java
private final AccountSpi              accountSpi;
private final MatchCalculationService matchCalculationService;
private final DueDateCalculationService dueDateCalculationService;
private final Clock                   clock;
```

All `final` (immutable), injected via `@RequiredArgsConstructor`

#### Lines 36-79: Process Method - Complete Breakdown

```java
@Override
public PaymentResult process(String userId, BigDecimal paymentAmount) {
    // Step 1: Validate payment amount
    if (paymentAmount.compareTo(BigDecimal.ZERO) <= 0) {
        throw new InvalidPaymentAmountException(
                "Payment amount must be greater than zero, but was: " + paymentAmount);
    }

    // Step 2: Load account
    Account account = accountSpi.findById(userId)
            .orElseThrow(() -> new AccountNotFoundException(
                    "Account not found for userId: " + userId));

    // Step 3: Calculate payment details
    BigDecimal previousBalance = account.getBalance();
    int        matchPercentage = matchCalculationService.getMatchPercentage(paymentAmount);
    BigDecimal matchAmount     = matchCalculationService.calculateMatchAmount(paymentAmount);
    BigDecimal totalDeduction  = paymentAmount.add(matchAmount);

    // Step 4: Validate sufficient balance
    if (totalDeduction.compareTo(previousBalance) > 0) {
        throw new InsufficientBalanceException(
                "Payment $" + paymentAmount + " + match $" + matchAmount
                        + " exceeds balance $" + previousBalance);
    }

    // Step 5: Debit account
    BigDecimal newBalance      = previousBalance
                                     .subtract(totalDeduction)
                                     .setScale(2, RoundingMode.HALF_UP);
    LocalDate  today           = LocalDate.now(clock);
    LocalDate  nextDueDate     = dueDateCalculationService.calculateDueDate(today);

    account.setBalance(newBalance);
    accountSpi.save(account);

    // Step 6: Log and return result
    log.info("Payment processed: userId={} payment={} match={}% matchAmt={} prev={} new={} due={}",
            userId, paymentAmount, matchPercentage, matchAmount, previousBalance, newBalance, nextDueDate);

    return new PaymentResult(
            userId,
            previousBalance,
            paymentAmount,
            matchPercentage,
            matchAmount,
            newBalance,
            nextDueDate,
            today);
}
```

**Detailed Step-by-Step Breakdown:**

**Step 1: Validate Payment Amount (Lines 38-41)**
```java
if (paymentAmount.compareTo(BigDecimal.ZERO) <= 0) {
    throw new InvalidPaymentAmountException(
            "Payment amount must be greater than zero, but was: " + paymentAmount);
}
```
- Uses `compareTo()` for safe BigDecimal comparison (never use `equals()`)
- Rejects zero and negative amounts
- Throws domain exception (mapped to 400 by GlobalExceptionHandler)

**Step 2: Load Account (Lines 43-45)**
```java
Account account = accountSpi.findById(userId)
        .orElseThrow(() -> new AccountNotFoundException(
                "Account not found for userId: " + userId));
```
- Queries account from persistence via SPI (abstraction)
- If not found: throws exception (mapped to 404)
- Optional pattern: safe null handling

**Step 3: Calculate Payment Details (Lines 47-51)**
```java
BigDecimal previousBalance = account.getBalance();
int        matchPercentage = matchCalculationService.getMatchPercentage(paymentAmount);
BigDecimal matchAmount     = matchCalculationService.calculateMatchAmount(paymentAmount);
BigDecimal totalDeduction  = paymentAmount.add(matchAmount);
```
- Stores previous balance (needed in response)
- Gets match percentage (1%, 3%, or 5% based on amount tier)
- Calculates match dollar amount
- Adds payment + match to get total deduction

**Step 4: Validate Sufficient Balance (Lines 53-56)**
```java
if (totalDeduction.compareTo(previousBalance) > 0) {
    throw new InsufficientBalanceException(
            "Payment $" + paymentAmount + " + match $" + matchAmount
                    + " exceeds balance $" + previousBalance);
}
```
- Business rule: payment + match cannot exceed balance
- Throws domain exception (mapped to 422 - Unprocessable Entity)
- Semantic HTTP status: request is valid but violates business rule

**Step 5: Debit Account (Lines 58-64)**
```java
BigDecimal newBalance      = previousBalance
                                 .subtract(totalDeduction)
                                 .setScale(2, RoundingMode.HALF_UP);
LocalDate  today           = LocalDate.now(clock);
LocalDate  nextDueDate     = dueDateCalculationService.calculateDueDate(today);

account.setBalance(newBalance);
accountSpi.save(account);
```
- Subtracts total deduction from previous balance
- `setScale(2, RoundingMode.HALF_UP)`: Ensures exactly 2 decimal places, rounds 0.5 up
- Gets today's date from injected clock (testable, can be fixed)
- Calculates next payment due date (today + 15 days, adjusted for weekends)
- Updates account object
- Persists via SPI (abstraction)

**Step 6: Log and Return Result (Lines 66-78)**
```java
log.info("Payment processed: userId={} payment={} match={}% matchAmt={} prev={} new={} due={}",
        userId, paymentAmount, matchPercentage, matchAmount, previousBalance, newBalance, nextDueDate);

return new PaymentResult(
        userId,
        previousBalance,
        paymentAmount,
        matchPercentage,
        matchAmount,
        newBalance,
        nextDueDate,
        today);
```
- Logs the successful payment with all details (audit trail)
- Constructs and returns PaymentResult record with all computed values

**Design Pattern: Use-Case/Interactor**
- Pure domain logic (no HTTP, no persistence details)
- Single responsibility: process payment
- Validates inputs and business rules
- Coordinates domain services
- Returns value object (not a generic string)

---

### PaymentResult.java

**Location:** `domain/src/main/java/com/customercare/domain/payment/PaymentResult.java`

**Purpose:** Immutable value object returned by payment use-case. Contains all data needed for HTTP response.

#### Complete File Breakdown

```java
package com.customercare.domain.payment;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Immutable value object returned by {@link ProcessPaymentUseCase}.
 *
 * <p>Contains every field needed to build the HTTP response without leaking
 * any contract DTO into the domain layer.
 *
 * @param userId               the customer's user identifier
 * @param previousBalance      account balance <em>before</em> the payment
 * @param paymentAmount        the amount paid
 * @param matchPercentage      tier-based match percentage (1, 3, or 5)
 * @param matchAmount          dollar amount matched
 * @param newBalance           account balance after payment + match deduction
 * @param nextPaymentDueDate   weekend-adjusted next payment due date
 * @param paymentDate          server-recorded date the payment was processed
 */
public record PaymentResult(
        String     userId,
        BigDecimal previousBalance,
        BigDecimal paymentAmount,
        int        matchPercentage,
        BigDecimal matchAmount,
        BigDecimal newBalance,
        LocalDate  nextPaymentDueDate,
        LocalDate  paymentDate) {
}
```

**Key Concepts:**

| Feature | Explanation |
|---------|------------|
| **Record** | Java immutable data carrier (Java 16+) |
| **Immutable** | Fields are `final`, no setters |
| **Auto-generated** | `equals()`, `hashCode()`, `toString()`, constructor |
| **Purpose** | Carry payment computation results without REST DTOs |
| **Field Purpose** | Each field has a specific use in the HTTP response |

**Field Breakdown:**

| Field | Type | Purpose |
|-------|------|---------|
| `userId` | String | Customer identifier |
| `previousBalance` | BigDecimal | Balance before payment (for audit) |
| `paymentAmount` | BigDecimal | What customer paid |
| `matchPercentage` | int | Tier-based percentage (1, 3, or 5) |
| `matchAmount` | BigDecimal | Dollar amount system matched |
| `newBalance` | BigDecimal | Balance after payment and deduction |
| `nextPaymentDueDate` | LocalDate | When next payment is due (weekend-adjusted) |
| `paymentDate` | LocalDate | Date payment was processed |

**Benefits of Record:**
- ✅ Immutable (thread-safe)
- ✅ Minimal boilerplate (no constructors, getters, equals, hashCode)
- ✅ Auto-documentation via field names
- ✅ Semantic meaning (value object, not just a container)
- ✅ Maps cleanly to REST response via MapStruct

**Mapping Path:**
```
PaymentService.process() → PaymentResult
    ↓
PaymentResponseMapper.toResponse() → OneTimePaymentResponse (REST DTO)
    ↓
Controller returns ResponseEntity<OneTimePaymentResponse>
```

---

### Account.java

**Location:** `domain/src/main/java/com/customercare/domain/model/Account.java`

**Purpose:** Domain model representing a customer account. Pure domain object with no persistence annotations.

#### Complete File Breakdown

```java
package com.customercare.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Domain model representing a customer account.
 *
 * <p>This is a pure domain object — it carries no persistence annotations.
 * Redis-specific mapping (e.g. {@code @RedisHash}) lives in the infra module's
 * {@code AccountEntity}, which is mapped to/from this class by {@code AccountEntityMapper}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Account {

    /** Unique user identifier. */
    private String userId;

    /** Current outstanding balance (scale 2). */
    private BigDecimal balance;
}
```

**Architectural Principle: Separation of Concerns**

| Layer | Class | Annotations |
|-------|-------|------------|
| **Domain** | Account | None (pure POJO) |
| **Infra** | AccountEntity | `@RedisHash`, `@Id`, etc. |
| **Mapping** | AccountEntityMapper | MapStruct `@Mapper` |

**Lombok Annotations Breakdown:**

| Annotation | Generated Code |
|-----------|--|
| `@Data` | Getters, setters, `equals()`, `hashCode()`, `toString()` |
| `@Builder` | Builder pattern (fluent object construction) |
| `@NoArgsConstructor` | Default constructor (no args) |
| `@AllArgsConstructor` | Constructor with all fields |

**Benefits of This Design:**
- ✅ Domain is framework-agnostic (can switch from Redis to JPA)
- ✅ No Spring/Jakarta imports in pure domain
- ✅ AccountEntity can change without affecting domain
- ✅ Mapstruct handles conversion (AccountEntity ↔ Account)

**Example Usage (Builder Pattern):**
```java
Account account = Account.builder()
    .userId("user123")
    .balance(new BigDecimal("150.00"))
    .build();
```

---

### AccountSpi.java

**Location:** `domain/src/main/java/com/customercare/domain/spi/AccountSpi.java`

**Purpose:** Secondary port (SPI) defining the persistence abstraction for accounts.

#### Complete File Breakdown

```java
package com.customercare.domain.spi;

import com.customercare.domain.model.Account;

import java.util.Optional;

/**
 * Secondary port (SPI) — declares what the domain needs from a persistence store.
 *
 * <p>The domain defines this interface; the infra module provides the implementation
 * ({@code AccountAdapter}) backed by Redis. To swap to any other store, only the infra
 * adapter changes — the domain and app modules remain untouched.
 */
public interface AccountSpi {

    /**
     * Retrieves an account by its user identifier.
     *
     * @param userId the unique user identifier
     * @return the account wrapped in {@link Optional}, or empty if not found
     */
    Optional<Account> findById(String userId);

    /**
     * Persists (creates or updates) the given account.
     *
     * @param account the account to save
     * @return the saved account
     */
    Account save(Account account);
}
```

**Architectural Context: Hexagonal Architecture (Ports & Adapters)**

**Secondary Port** (Outbound Interface):
- Defined by domain
- Implemented by infrastructure (`AccountAdapter`)
- Called by domain services (e.g., `ProcessPaymentService`)
- SPI = Service Provider Interface

**Interface Methods:**

| Method | Purpose | Returns |
|--------|---------|---------|
| `findById(userId)` | Query account by ID | `Optional<Account>` (safe null handling) |
| `save(account)` | Persist account (create or update) | Updated account |

**Design Benefits:**
- ✅ Domain doesn't know about Redis/JPA/etc
- ✅ Easy to mock for testing
- ✅ Pluggable: swap AccountAdapter implementation without changing domain
- ✅ `Optional` prevents null pointer exceptions

**Usage in ProcessPaymentService:**
```java
Account account = accountSpi.findById(userId)
        .orElseThrow(() -> new AccountNotFoundException(...));
```

---

### IdempotencyStoreSpi.java

**Location:** `domain/src/main/java/com/customercare/domain/spi/IdempotencyStoreSpi.java`

**Purpose:** Secondary port for idempotency caching. Allows storing and retrieving cached responses.

#### Complete File Breakdown

```java
package com.customercare.domain.spi;

import java.util.Optional;

/**
 * Secondary port (SPI) — idempotency key-value store.
 *
 * <p>Allows callers to cache a response by key and retrieve it later,
 * preventing duplicate processing of the same request. The infra module
 * provides a Redis-backed implementation with a 24-hour TTL.
 */
public interface IdempotencyStoreSpi {

    /**
     * Retrieves a previously cached value.
     *
     * @param key  the idempotency key
     * @param type the expected value type (used for deserialization)
     * @return the cached value, or empty if not found
     */
    <T> Optional<T> find(String key, Class<T> type);

    /**
     * Caches a value under the given key.
     *
     * @param key   the idempotency key
     * @param value the value to cache (serialized internally)
     */
    void store(String key, Object value);
}
```

**Key Concepts:**

| Method | Signature | Purpose |
|--------|-----------|---------|
| `find()` | `<T> Optional<T> find(String key, Class<T> type)` | Retrieve cached value by key, deserialized to type T |
| `store()` | `void store(String key, Object value)` | Store any object under key (serialized internally) |

**Generic Type Parameter `<T>`:**
- Enables type-safe retrieval: `Optional<PaymentResponse>`
- `Class<T> type`: Used for deserialization (Jackson needs target class)
- Works with any response type (payments, refunds, etc.)

**Redis Implementation Details:**
- Key prefix: `idempotency:` (e.g., `idempotency:abc-123`)
- TTL: 24 hours (prevents unbounded cache growth)
- Storage: JSON serialization via Jackson

**Usage in IdempotencyGuard:**
```java
Optional<T> cached = idempotencyStore.find(key, OneTimePaymentResponse.class);
```

---

### MatchCalculationService.java

**Location:** `domain/src/main/java/com/customercare/domain/service/MatchCalculationService.java`

**Purpose:** Domain service interface defining match calculation rules.

#### Complete File Breakdown

```java
package com.customercare.domain.service;

import java.math.BigDecimal;

/**
 * Calculates the match percentage and match amount for a given payment amount.
 *
 * <p>Tier table:
 * <ul>
 *   <li>{@code 0 < amount < 10}   → 1 %</li>
 *   <li>{@code 10 <= amount < 50} → 3 %</li>
 *   <li>{@code amount >= 50}      → 5 %</li>
 * </ul>
 */
public interface MatchCalculationService {

    /**
     * Returns the applicable match percentage (1, 3, or 5) for the given payment amount.
     *
     * @param paymentAmount the payment amount; must be {@code > 0}
     * @return match percentage as an integer
     */
    int getMatchPercentage(BigDecimal paymentAmount);

    /**
     * Calculates the match dollar amount (rounded to 2 decimal places, HALF_UP).
     *
     * @param paymentAmount the payment amount; must be {@code > 0}
     * @return match amount
     */
    BigDecimal calculateMatchAmount(BigDecimal paymentAmount);
}
```

**Business Rules: Tier-Based Matching**

| Amount Range | Match % |
|--------------|---------|
| 0 < amount < 10 | 1% |
| 10 ≤ amount < 50 | 3% |
| amount ≥ 50 | 5% |

**Example Calculations:**
- Payment $5 → Match 1% = $0.05
- Payment $25 → Match 3% = $0.75
- Payment $100 → Match 5% = $5.00

**Why Two Methods?**
- `getMatchPercentage()`: Returns tier (needed in response)
- `calculateMatchAmount()`: Returns dollar amount (needed for balance deduction)

**BigDecimal Rounding:**
- Always round to 2 decimal places (money)
- `RoundingMode.HALF_UP`: 0.125 → 0.13 (banker's rounding)

---

### MatchCalculationServiceImpl.java

**Location:** `domain/src/main/java/com/customercare/domain/service/impl/MatchCalculationServiceImpl.java`

**Purpose:** Implements tier-based match calculation logic.

#### Complete File Breakdown

```java
package com.customercare.domain.service.impl;

import com.customercare.domain.service.MatchCalculationService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Tier-based match calculation.
 *
 * <p>All comparisons use {@link BigDecimal#compareTo} to avoid {@code equals()} scale issues.
 */
@Service
public class MatchCalculationServiceImpl implements MatchCalculationService {

    private static final BigDecimal TEN     = BigDecimal.valueOf(10);
    private static final BigDecimal FIFTY   = BigDecimal.valueOf(50);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Override
    public int getMatchPercentage(BigDecimal paymentAmount) {
        if (paymentAmount.compareTo(TEN) < 0) {
            return 1;
        } else if (paymentAmount.compareTo(FIFTY) < 0) {
            return 3;
        } else {
            return 5;
        }
    }

    @Override
    public BigDecimal calculateMatchAmount(BigDecimal paymentAmount) {
        int pct = getMatchPercentage(paymentAmount);
        return paymentAmount
                .multiply(BigDecimal.valueOf(pct))
                .divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }
}
```

**Detailed Breakdown:**

**Class Declaration:**
```java
@Service
public class MatchCalculationServiceImpl implements MatchCalculationService {
```
- `@Service`: Spring-managed bean (available for injection)
- Implements interface (contract defined in domain)

**Static Constants (Lines 17-19):**
```java
private static final BigDecimal TEN     = BigDecimal.valueOf(10);
private static final BigDecimal FIFTY   = BigDecimal.valueOf(50);
private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
```
- Pre-computed BigDecimal objects (avoid creating new instances)
- Improves performance and memory usage
- `valueOf()` is preferred over `new BigDecimal()` for whole numbers

**getMatchPercentage() Method (Lines 21-30):**
```java
if (paymentAmount.compareTo(TEN) < 0) {
    return 1;
} else if (paymentAmount.compareTo(FIFTY) < 0) {
    return 3;
} else {
    return 5;
}
```

**Why `compareTo()` and not `equals()`?**

BigDecimal gotcha:
```java
new BigDecimal("10.00").equals(new BigDecimal("10")) // false! Different scale
new BigDecimal("10.00").compareTo(new BigDecimal("10")) // Returns 0 (equal)
```

- `compareTo()` ignores scale (only compares numeric value)
- Returns: -1 (less), 0 (equal), +1 (greater)

**Tier Logic:**
- `< 10` → 1%
- `≥ 10 && < 50` → 3%
- `≥ 50` → 5%

**calculateMatchAmount() Method (Lines 32-38):**
```java
int pct = getMatchPercentage(paymentAmount);
return paymentAmount
        .multiply(BigDecimal.valueOf(pct))
        .divide(HUNDRED, 2, RoundingMode.HALF_UP);
```

**Step-by-step:**
1. Get match percentage (1, 3, or 5)
2. Multiply payment amount by percentage: `$100 * 3 = $300`
3. Divide by 100 to get percentage: `$300 / 100 = $3.00`
4. `.divide(HUNDRED, 2, RoundingMode.HALF_UP)`: 
   - Second parameter `2`: Scale (decimal places)
   - `RoundingMode.HALF_UP`: Round 0.5 up (e.g., 0.125 → 0.13)

**Example Execution:**
```
Input: $25.00
1. getMatchPercentage($25) → 3 (because 25 >= 10 && 25 < 50)
2. $25 * 3 / 100 = $0.75 ✓
```

---

### DueDateCalculationService.java

**Location:** `domain/src/main/java/com/customercare/domain/service/DueDateCalculationService.java`

**Purpose:** Domain service interface for calculating payment due dates.

#### Complete File Breakdown

```java
package com.customercare.domain.service;

import java.time.LocalDate;

/**
 * Calculates the next payment due date as payment date + 15 days,
 * adjusting Saturday → Monday and Sunday → Monday.
 */
public interface DueDateCalculationService {

    /**
     * Returns the weekend-adjusted due date.
     *
     * @param paymentDate the date on which the payment was made
     * @return due date (never a Saturday or Sunday)
     */
    LocalDate calculateDueDate(LocalDate paymentDate);
}
```

**Business Rule:**
- Add 15 days to payment date
- If result lands on Saturday → shift to Monday (+2 days)
- If result lands on Sunday → shift to Monday (+1 day)
- Weekdays remain unchanged

**Examples:**
- Payment Friday, April 10 → Due date: April 25 (Saturday) → Adjust to April 27 (Monday)
- Payment Saturday, April 11 → Due date: April 26 (Sunday) → Adjust to April 28 (Monday)
- Payment Monday, April 12 → Due date: April 27 (Monday) → No adjustment

**Purpose of Adjustment:**
- Business operations typically close on weekends
- Ensures customers don't have due dates they can't pay on

---

### DueDateCalculationServiceImpl.java

**Location:** `domain/src/main/java/com/customercare/domain/service/impl/DueDateCalculationServiceImpl.java`

**Purpose:** Implements weekend-adjusted due date calculation.

#### Complete File Breakdown

```java
package com.customercare.domain.service.impl;

import com.customercare.domain.service.DueDateCalculationService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * Calculates payment due date as payment date + 15 days,
 * then shifts Saturday → Monday (+2) and Sunday → Monday (+1).
 */
@Service
public class DueDateCalculationServiceImpl implements DueDateCalculationService {

    @Override
    public LocalDate calculateDueDate(LocalDate paymentDate) {
        LocalDate rawDueDate = paymentDate.plusDays(15);

        return switch (rawDueDate.getDayOfWeek()) {
            case SATURDAY -> rawDueDate.plusDays(2);
            case SUNDAY   -> rawDueDate.plusDays(1);
            default       -> rawDueDate;
        };
    }
}
```

**Implementation Breakdown:**

**Line 18: Add 15 Days**
```java
LocalDate rawDueDate = paymentDate.plusDays(15);
```
- `plusDays()`: Adds days to date (handles month/year boundaries automatically)
- Immutable: returns new LocalDate

**Lines 20-24: Weekend Adjustment (Pattern Matching)**
```java
return switch (rawDueDate.getDayOfWeek()) {
    case SATURDAY -> rawDueDate.plusDays(2);
    case SUNDAY   -> rawDueDate.plusDays(1);
    default       -> rawDueDate;
};
```

**Modern Java Features:**
- `switch` expression (not statement): evaluates to a value
- `->` arrow syntax: no need for `break` statements
- Pattern matching: exhaustive (compiler checks all cases)

**Logic:**
- Saturday + 2 = Monday ✓
- Sunday + 1 = Monday ✓
- Monday-Friday: no change

**Example Trace:**
```
Input: April 10, 2026 (Friday)
rawDueDate = April 10 + 15 = April 25, 2026 (Saturday)
getDayOfWeek() = SATURDAY
return April 25 + 2 = April 27, 2026 (Monday) ✓
```

---

### Exception Classes

**Location:** `domain/src/main/java/com/customercare/domain/exception/`

Three domain exceptions handle different error scenarios:

#### AccountNotFoundException.java

```java
package com.customercare.domain.exception;

/**
 * Thrown when a requested account does not exist.
 * Maps to HTTP 404 via the global exception handler in the app module.
 */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(String message) {
        super(message);
    }
}
```

- **Extends RuntimeException**: Unchecked exception (doesn't require catching)
- **When thrown**: `accountSpi.findById()` returns empty Optional
- **HTTP Mapping**: 404 Not Found (via GlobalExceptionHandler)
- **Usage**: `throw new AccountNotFoundException("Account not found for userId: " + userId)`

#### InsufficientBalanceException.java

```java
package com.customercare.domain.exception;

/**
 * Thrown when the payment amount plus match exceeds the account balance.
 * Maps to HTTP 422 via the global exception handler in the app module.
 */
public class InsufficientBalanceException extends RuntimeException {

    public InsufficientBalanceException(String message) {
        super(message);
    }
}
```

- **When thrown**: `(paymentAmount + matchAmount) > accountBalance`
- **HTTP Mapping**: 422 Unprocessable Entity (semantic error, not syntax)
- **Business Meaning**: Request is valid but violates business rule

#### InvalidPaymentAmountException.java

```java
package com.customercare.domain.exception;

/**
 * Thrown when a payment amount is zero or negative.
 * Maps to HTTP 400 via the global exception handler in the app module.
 */
public class InvalidPaymentAmountException extends RuntimeException {

    public InvalidPaymentAmountException(String message) {
        super(message);
    }
}
```

- **When thrown**: `paymentAmount <= 0`
- **HTTP Mapping**: 400 Bad Request (client input error)
- **Validation Point**: First thing in `ProcessPaymentService.process()`

**Design Pattern: Domain Exceptions**
- Pure business/domain knowledge
- No infrastructure dependencies
- GlobalExceptionHandler translates to HTTP
- Stacktraces never exposed to clients

---

## Infrastructure Layer (infra/)

### RedisConfig.java

**Location:** `infra/src/main/java/com/customercare/infra/config/RedisConfig.java`

**Purpose:** Configures Redis template for serialization of keys and values.

#### Complete File Breakdown

```java
package com.customercare.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis infrastructure configuration.
 *
 * <p>{@code RedisConnectionFactory} is auto-configured by Spring Boot using
 * {@code spring.data.redis.*} properties from application resources in the
 * bootstrap module.
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        return template;
    }
}
```

**Configuration Breakdown:**

**@Configuration**
```java
@Configuration
public class RedisConfig {
```
- Marks class as Spring configuration (contains @Bean methods)
- Beans are registered in ApplicationContext

**RedisTemplate Bean**
```java
@Bean
public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
```
- `@Bean`: Method returns managed Spring bean
- `connectionFactory`: Auto-injected by Spring Boot
- Generic type: `<String, Object>` means keys are strings, values are any object

**Serializer Configuration:**

| Method | Serializer | Purpose |
|--------|-----------|---------|
| `setKeySerializer()` | StringRedisSerializer | Keys stored as plain strings in Redis |
| `setHashKeySerializer()` | StringRedisSerializer | Hash field names as strings |
| `setValueSerializer()` | GenericJackson2JsonRedisSerializer | Values serialized to JSON |
| `setHashValueSerializer()` | GenericJackson2JsonRedisSerializer | Hash values serialized to JSON |

**Why JSON Serialization?**
- ✅ Human-readable in Redis CLI
- ✅ Language-agnostic (other services can read)
- ✅ Jackson handles complex objects automatically
- ✅ Works with generics (knows target class at runtime)

**Example:**
```
Key: "idempotency:abc-123"
Value: {"userId":"user1","newBalance":150.50,...} (JSON string)
```

**Auto-Configuration:**
- `RedisConnectionFactory` is auto-configured by Spring Boot
- Connection details come from `application.yml`:
  ```yaml
  spring:
    data:
      redis:
        host: localhost
        port: 6379
  ```

---

### AccountAdapter.java

**Location:** `infra/src/main/java/com/customercare/infra/redis/adapter/AccountAdapter.java`

**Purpose:** Implements the domain's `AccountSpi` using Redis via Spring Data.

#### Complete File Breakdown

```java
package com.customercare.infra.redis.adapter;

import com.customercare.domain.model.Account;
import com.customercare.domain.spi.AccountSpi;
import com.customercare.infra.redis.mapper.AccountEntityMapper;
import com.customercare.infra.redis.repository.AccountRedisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Secondary-port adapter that fulfils the domain's {@link AccountSpi} contract
 * using Spring Data Redis ({@link AccountRedisRepository}).
 *
 * <p>This is the only class that needs to change if the persistence store is
 * swapped (e.g. Redis → JPA).  The domain and app modules are unaffected.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountAdapter implements AccountSpi {

    private final AccountRedisRepository accountRedisRepository;
    private final AccountEntityMapper    accountEntityMapper;

    @Override
    public Optional<Account> findById(String userId) {
        log.debug("Looking up account: userId={}", userId);
        Optional<Account> result = accountRedisRepository.findById(userId)
                .map(accountEntityMapper::toDomain);
        if (result.isEmpty()) {
            log.debug("Account not found in Redis: userId={}", userId);
        }
        return result;
    }

    @Override
    public Account save(Account account) {
        log.debug("Saving account: userId={} balance={}", account.getUserId(), account.getBalance());
        return accountEntityMapper.toDomain(
                accountRedisRepository.save(accountEntityMapper.toEntity(account)));
    }
}
```

**Architecture: Secondary Port Adapter**

```
Domain Layer:
  ↑ Depends on AccountSpi (interface)
  
Infra Layer:
  ↓ Implements AccountSpi
  AccountAdapter (this class)
    ↓
  Uses Spring Data Redis
    ↓
  Redis backend
```

**Dependencies (Injected):**

| Dependency | Role |
|-----------|------|
| `AccountRedisRepository` | Spring Data interface (CRUD operations) |
| `AccountEntityMapper` | MapStruct converter (Entity ↔ Domain) |

**findById() Method:**

```java
public Optional<Account> findById(String userId) {
    log.debug("Looking up account: userId={}", userId);
    Optional<Account> result = accountRedisRepository.findById(userId)
            .map(accountEntityMapper::toDomain);
    if (result.isEmpty()) {
        log.debug("Account not found in Redis: userId={}", userId);
    }
    return result;
}
```

**Step breakdown:**
1. Log debug message (lightweight, only if DEBUG level enabled)
2. Call repository `findById(userId)` returns `Optional<AccountEntity>`
3. `.map(accountEntityMapper::toDomain)`: 
   - If entity exists: convert to Account (domain model)
   - If empty: stays empty
4. Log if not found for audit trail
5. Return `Optional<Account>` to domain layer

**Optional chaining:**
```
Query finds entity? → Convert to Account → Return Optional with Account
Query finds nothing? → Stay empty → Return empty Optional
```

**save() Method:**

```java
public Account save(Account account) {
    log.debug("Saving account: userId={} balance={}", account.getUserId(), account.getBalance());
    return accountEntityMapper.toDomain(
            accountRedisRepository.save(accountEntityMapper.toEntity(account)));
}
```

**Conversion chain:**
1. Domain `Account` input
2. `toEntity()`: Convert to Redis `AccountEntity`
3. `save()`: Persist to Redis (Spring Data handles serialization)
4. `toDomain()`: Convert result back to domain `Account`
5. Return domain `Account` to caller

**Why map both ways?**
- ✅ Domain never sees Redis annotations
- ✅ Easy to swap Redis for JPA: only change this adapter
- ✅ Clear separation: what's domain, what's infra

**Logging:**
- DEBUG level: cheap (minimal overhead if not enabled)
- Useful for infrastructure troubleshooting
- Includes userId and balance for audit

---

### RedisIdempotencyStore.java

**Location:** `infra/src/main/java/com/customercare/infra/redis/adapter/RedisIdempotencyStore.java`

**Purpose:** Implements `IdempotencyStoreSpi` using Redis with 24-hour TTL.

#### Complete File Breakdown

```java
package com.customercare.infra.redis.adapter;

import com.customercare.domain.spi.IdempotencyStoreSpi;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed idempotency store with a 24-hour TTL.
 *
 * <p>Values are serialized to JSON via Jackson so that any DTO can be
 * stored and retrieved by type. Serialization errors are logged and
 * swallowed — a failing idempotency cache must never block a payment.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisIdempotencyStore implements IdempotencyStoreSpi {

    private static final Duration TTL    = Duration.ofHours(24);
    private static final String   PREFIX = "idempotency:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper        objectMapper;

    @Override
    public <T> Optional<T> find(String key, Class<T> type) {
        String json = redisTemplate.opsForValue().get(PREFIX + key);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, type));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize cached response for idempotency key={}", key, e);
            return Optional.empty();
        }
    }

    @Override
    public void store(String key, Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(PREFIX + key, json, TTL);
            log.debug("Idempotency response cached: key={}", key);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize response for idempotency key={}", key, e);
        }
    }
}
```

**Design: Fail-Safe Caching**

Important principle: **Idempotency cache failures must never block payments**

If cache fails → execute operation again (safe by idempotency key) → better than blocking customer

**Constants:**

```java
private static final Duration TTL    = Duration.ofHours(24);
private static final String   PREFIX = "idempotency:";
```

- **TTL**: 24 hours (prevents unbounded cache growth, accommodates retry windows)
- **PREFIX**: All idempotency entries prefixed with `idempotency:` for organization

**find() Method - Retrieve Cached Response:**

```java
public <T> Optional<T> find(String key, Class<T> type) {
    String json = redisTemplate.opsForValue().get(PREFIX + key);
    if (json == null) {
        return Optional.empty();
    }
    try {
        return Optional.of(objectMapper.readValue(json, type));
    } catch (JsonProcessingException e) {
        log.error("Failed to deserialize cached response for idempotency key={}", key, e);
        return Optional.empty();
    }
}
```

**Step breakdown:**

1. **Construct full key**: `"idempotency:" + key`
2. **Query Redis**: `get()` returns JSON string (or null if not found)
3. **Check existence**: `if (json == null)` → return empty Optional
4. **Deserialize JSON**:
   ```java
   objectMapper.readValue(json, type)
   ```
   - Jackson parses JSON string
   - `type` parameter: target class (determines what to deserialize to)
   - Example: `readValue(json, OneTimePaymentResponse.class)`

5. **Error handling**: 
   - If deserialization fails (corrupted data, version mismatch): log error, return empty
   - Fail-safe: better to execute again than crash

6. **Return**: `Optional<T>` with deserialized response or empty

**Example Flow:**
```
Input: key="abc-123", type=OneTimePaymentResponse.class
Redis contains: "idempotency:abc-123" → {"userId":"user1","newBalance":150.50}
Output: Optional.of(new OneTimePaymentResponse(...))
```

**store() Method - Cache Response:**

```java
public void store(String key, Object value) {
    try {
        String json = objectMapper.writeValueAsString(value);
        redisTemplate.opsForValue().set(PREFIX + key, json, TTL);
        log.debug("Idempotency response cached: key={}", key);
    } catch (JsonProcessingException e) {
        log.error("Failed to serialize response for idempotency key={}", key, e);
    }
}
```

**Step breakdown:**

1. **Serialize to JSON**:
   ```java
   objectMapper.writeValueAsString(value)
   ```
   - Converts any object to JSON string
   - Works with any DTO (PaymentResponse, etc.)

2. **Store in Redis with TTL**:
   ```java
   redisTemplate.opsForValue().set(PREFIX + key, json, TTL)
   ```
   - Key: `"idempotency:" + key`
   - Value: JSON string
   - TTL: 24 hours (auto-expires)

3. **Log success**: DEBUG level (low overhead)

4. **Error handling**:
   - If serialization fails: log error, swallow exception
   - Fail-safe: cache miss, operation executes again

**Why Fail-Safe?**
- Cache failures = minor performance hit (duplicate work)
- Cache blocking payments = critical failure (customers can't pay)
- Always biased towards **availability** over consistency

---

### AccountEntity.java

**Location:** `infra/src/main/java/com/customercare/infra/redis/entity/AccountEntity.java`

**Purpose:** Redis persistence entity for accounts. Owns all Redis/DB annotations.

#### Complete File Breakdown

```java
package com.customercare.infra.redis.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;

import java.math.BigDecimal;

/**
 * Redis persistence entity for an account.
 *
 * <p>This class owns all Redis/Spring-Data annotations so the domain model
 * ({@code Account}) can remain a plain POJO.  {@code AccountEntityMapper}
 * translates between the two representations.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@RedisHash("account")
public class AccountEntity {

    @Id
    private String userId;

    private BigDecimal balance;
}
```

**Design Pattern: Repository Pattern with Entity Layer**

**Separation of Concerns:**

| Class | Annotations | Purpose |
|-------|------------|---------|
| **Account** (domain) | None | Business logic, pure POJO |
| **AccountEntity** (infra) | `@RedisHash`, `@Id` | Persistence concern, Redis-specific |
| **AccountEntityMapper** | Conversion | Translates between layers |

**Why Separate?**

- ✅ Domain doesn't depend on Spring Data/Redis
- ✅ If you swap Redis to JPA: only change AccountEntity and mapper
- ✅ Domain model stays clean and framework-agnostic
- ✅ Clear what's persistence detail vs. business logic

**Annotations Breakdown:**

| Annotation | Purpose |
|-----------|---------|
| `@RedisHash("account")` | Maps to Redis hash with prefix "account" |
| `@Id` | Designates userId as Redis key |
| `@Data` | Generates getters, setters, `equals()`, `hashCode()` |
| `@Builder` | Builder pattern (fluent construction) |

**How it Works in Redis:**

```
Redis CLI view:
> KEYS *
1) "account:user123"

> HGETALL "account:user123"
1) "userId"
2) "user123"
3) "balance"
4) "150.50"
```

- Key: `"account:" + userId` (automatic from `@RedisHash` + `@Id`)
- Value: Hash (multiple fields: userId, balance)
- Spring Data Redis handles serialization automatically

---

### AccountEntityMapper.java

**Location:** `infra/src/main/java/com/customercare/infra/redis/mapper/AccountEntityMapper.java`

**Purpose:** MapStruct mapper converting between Redis entity and domain model.

#### Complete File Breakdown

```java
package com.customercare.infra.redis.mapper;

import com.customercare.domain.model.Account;
import com.customercare.infra.redis.entity.AccountEntity;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper translating between the Redis persistence entity
 * ({@link AccountEntity}) and the domain model ({@link Account}).
 *
 * <p>Generated implementation ({@code AccountEntityMapperImpl}) is created at
 * compile time.  Do <strong>not</strong> edit it by hand.
 */
@Mapper(componentModel = "spring")
public interface AccountEntityMapper {

    /** Converts a Redis entity to the domain model. */
    Account toDomain(AccountEntity entity);

    /** Converts a domain model to the Redis entity. */
    AccountEntity toEntity(Account account);
}
```

**Simple, Clean Mapper Interface**

**Methods:**

| Method | Purpose | Example |
|--------|---------|---------|
| `toDomain()` | Entity → Domain | `AccountEntity` → `Account` |
| `toEntity()` | Domain → Entity | `Account` → `AccountEntity` |

**Why MapStruct?**
- ✅ Compile-time code generation (zero reflection)
- ✅ Type-safe
- ✅ Fast (no runtime overhead)
- ✅ Auto-registers as Spring bean

**Generated Implementation (Created at Compile Time):**
```java
// Generated, do NOT edit manually
@Component
public class AccountEntityMapperImpl implements AccountEntityMapper {
    
    @Override
    public Account toDomain(AccountEntity entity) {
        if (entity == null) return null;
        return Account.builder()
            .userId(entity.getUserId())
            .balance(entity.getBalance())
            .build();
    }
    
    @Override
    public AccountEntity toEntity(Account account) {
        if (account == null) return null;
        return AccountEntity.builder()
            .userId(account.getUserId())
            .balance(account.getBalance())
            .build();
    }
}
```

**Note:** MapStruct will regenerate this every compile, so manual edits are lost!

---

### AccountRedisRepository.java

**Location:** `infra/src/main/java/com/customercare/infra/redis/repository/AccountRedisRepository.java`

**Purpose:** Spring Data Redis repository for CRUD operations on accounts.

#### Complete File Breakdown

```java
package com.customercare.infra.redis.repository;

import com.customercare.infra.redis.entity.AccountEntity;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data Redis repository for {@link AccountEntity}.
 *
 * <p>Spring auto-generates the implementation at startup.  This interface is intentionally
 * kept in the infra module so that the domain never sees Redis-specific types.
 */
@Repository
public interface AccountRedisRepository extends CrudRepository<AccountEntity, String> {
}
```

**Spring Data Magic:**

| Aspect | Explanation |
|--------|------------|
| **Interface** | You provide the contract; Spring generates implementation |
| **CrudRepository** | Base interface with `save()`, `findById()`, `delete()`, etc. |
| **`<AccountEntity, String>`** | Entity type and ID type (userId is String) |
| **Auto-generated** | At startup, Spring creates `AccountRedisRepositoryImpl` |

**Generated Methods (ImpLibed from CrudRepository):**

```java
AccountEntity save(AccountEntity entity);
Optional<AccountEntity> findById(String id);
void deleteById(String id);
Iterable<AccountEntity> findAll();
boolean existsById(String id);
```

**Why Keep in Infra?**
- Domain never imports `AccountRedisRepository`
- Domain calls domain `AccountSpi`
- `AccountAdapter` (in infra) adapts `AccountRedisRepository` to `AccountSpi`
- If swapping Redis → JPA: domain untouched, only infra changes

---

## Bootstrap Layer (bootstrap/)

### CustomerCareApplication.java

**Location:** `bootstrap/src/main/java/com/customercare/CustomerCareApplication.java`

**Purpose:** Spring Boot application entry point. Scans and registers all beans across modules.

#### Complete File Breakdown

```java
package com.customercare;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point.
 *
 * <p>{@code @SpringBootApplication} scans from {@code com.customercare}, picking up
 * all {@code @Component}/{@code @Service}/{@code @Repository} beans across the
 * {@code domain}, {@code infra}, and {@code app} modules automatically.
 *
 * <p>The {@link java.time.Clock} bean is provided by
 * {@link com.customercare.config.ClockConfig} — set {@code app.fixed-date=YYYY-MM-DD}
 * in a profile config to pin the date for manual Swagger testing.
 */
@SpringBootApplication
public class CustomerCareApplication {

    public static void main(String[] args) {
        SpringApplication.run(CustomerCareApplication.class, args);
    }
}
```

**Breakdown:**

**@SpringBootApplication**
```java
@SpringBootApplication
```
- Meta-annotation combining:
  - `@Configuration`: This class defines beans
  - `@ComponentScan`: Scans for `@Component`, `@Service`, `@Repository`, etc.
  - `@EnableAutoConfiguration`: Auto-configures Spring Boot beans
- Base package: `com.customercare` (and all subpackages)
- Components discovered from all three modules (domain, infra, app)

**main() method**
```java
public static void main(String[] args) {
    SpringApplication.run(CustomerCareApplication.class, args);
}
```
- Entry point when running JAR or IDE
- `SpringApplication.run()`: 
  1. Creates ApplicationContext
  2. Scans for beans
  3. Injects dependencies
  4. Starts embedded Tomcat
  5. Application ready to handle requests

**Dependency Wiring (automatically):**

When bootstrap scans, it finds:

| Module | Classes Registered |
|--------|-------------------|
| **app** | `@RestController` (HelloController, PaymentController), `@Component` (GlobalExceptionHandler, IdempotencyGuard, PaymentResponseMapper) |
| **domain** | `@Service` (ProcessPaymentService, DueDateCalculationServiceImpl, MatchCalculationServiceImpl) |
| **infra** | `@Component` (AccountAdapter, RedisIdempotencyStore), `@Repository` (AccountRedisRepository), `@Configuration` (RedisConfig) |
| **bootstrap** | `@Configuration` (ClockConfig) |

All beans are wired automatically based on `@Autowired` and constructor injection.

---

### ClockConfig.java

**Location:** `bootstrap/src/main/java/com/customercare/config/ClockConfig.java`

**Purpose:** Provides `Clock` bean with ability to fix date for testing.

#### Complete File Breakdown

```java
package com.customercare.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Provides the application-wide {@link Clock} bean.
 *
 * <p>Two variants are available:
 *
 * <ul>
 *   <li><b>Fixed clock</b> — activated by setting {@code app.fixed-date=YYYY-MM-DD} in any
 *       Spring profile config (e.g. {@code application-local.yml}).  Useful for manually testing
 *       weekend due-date shifting via Swagger UI without waiting for the right calendar day.
 *
 *       <pre>
 *       Dates that trigger a Saturday shift → Monday in Swagger:
 *         app.fixed-date=2026-04-17   # Friday  +15 = 2026-05-02 (Sat) → 2026-05-04 (Mon)
 *         app.fixed-date=2026-04-18   # Saturday +15 = 2026-05-03 (Sun) → 2026-05-05 (Mon)
 *       </pre>
 *
 *   <li><b>System clock</b> — default when {@code app.fixed-date} is absent.
 * </ul>
 */
@Configuration
public class ClockConfig {

    private static final Logger log = LoggerFactory.getLogger(ClockConfig.class);

    /**
     * Fixed clock — used when {@code app.fixed-date} is configured.
     *
     * <p>The date string must be ISO-8601 format: {@code YYYY-MM-DD}.
     * The clock is anchored to midnight in the system's default timezone so that
     * {@code LocalDate.now(clock)} returns exactly the configured date.
     */
    @Bean
    @ConditionalOnProperty(name = "app.fixed-date")
    public Clock fixedClock(@Value("${app.fixed-date}") String fixedDate) {
        ZoneId zone = ZoneId.systemDefault();
        Clock clock = Clock.fixed(
                LocalDate.parse(fixedDate).atStartOfDay(zone).toInstant(),
                zone);
        log.warn("*** FIXED CLOCK ACTIVE — app.fixed-date={} ({}). Do NOT use in production. ***",
                fixedDate, LocalDate.parse(fixedDate).getDayOfWeek());
        return clock;
    }

    /**
     * Real system clock — default when {@code app.fixed-date} is not set.
     */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock systemClock() {
        return Clock.systemDefaultZone();
    }
}
```

**Conditional Bean Registration Pattern**

**Scenario 1: Fixed Clock (for Testing)**

```java
@Bean
@ConditionalOnProperty(name = "app.fixed-date")
public Clock fixedClock(@Value("${app.fixed-date}") String fixedDate) {
```

**Conditionals:**
- `@ConditionalOnProperty(name = "app.fixed-date")`: Only if property exists in config
- `@Value("${app.fixed-date}")`: Injects property value

**When activated:**
```yaml
# application-local.yml
app:
  fixed-date: 2026-04-17
```

**Implementation:**

```java
ZoneId zone = ZoneId.systemDefault();
Clock clock = Clock.fixed(
        LocalDate.parse(fixedDate).atStartOfDay(zone).toInstant(),
        zone);
```

**Step breakdown:**
1. Parse date string: `"2026-04-17"` → `LocalDate`
2. Convert to midnight (start of day): `LocalDate.atStartOfDay(zone)`
3. Convert to `Instant`: `toInstant()`
4. Create fixed clock from instant and timezone: `Clock.fixed()`

**Result:**
- `LocalDate.now(clock)` always returns exactly the configured date
- Time is always 00:00:00 (midnight)
- Timezone is system default

**Why Useful?**
- Test weekend shifting without waiting for Saturday
- Reproduce specific date scenarios reproducibly
- Manual testing via Swagger UI without complexity

**Example Test Dates:**
```
app.fixed-date=2026-04-17 (Friday)
  Payment on Friday, due date calculation:
  Friday + 15 days = Saturday (April 25)
  Saturday → Monday (April 27) ✓

app.fixed-date=2026-04-18 (Saturday)
  Payment on Saturday, due date calculation:
  Saturday + 15 days = Sunday (April 26)
  Sunday → Monday (April 27) ✓
```

**Warning Log:**
```java
log.warn("*** FIXED CLOCK ACTIVE — app.fixed-date={} ({}). Do NOT use in production. ***",
        fixedDate, LocalDate.parse(fixedDate).getDayOfWeek());
```
- **WARN level**: Gets visible immediately (important!)
- Message includes day of week for verification
- Explicitly warns about production risk

**Scenario 2: System Clock (Default)**

```java
@Bean
@ConditionalOnMissingBean(Clock.class)
public Clock systemClock() {
    return Clock.systemDefaultZone();
}
```

**Conditionals:**
- `@ConditionalOnMissingBean(Clock.class)`: Only if no other Clock bean exists
- Fallback when `app.fixed-date` is NOT set

**Implementation:**
- `Clock.systemDefaultZone()`: Real system time, current timezone
- Standard behavior in production

**Decision Logic:**
```
if (app.fixed-date property exists)
    return fixedClock
else if (no Clock bean registered yet)
    return systemClock
else
    (error: multiple Clock beans)
```

**Benefits of This Pattern:**
- ✅ Testable: easy to wire fixed date for reproducible tests
- ✅ Production-safe: system clock by default
- ✅ No code changes: just configuration
- ✅ Explicit warning: fixed clock usage is visible in logs

---

## Summary & Architecture Overview

### Layer Responsibilities

**Presentation Layer (app/)**
- Controllers: HTTP adapters (thin - no business logic)
- Exception handler: Maps exceptions to HTTP responses
- Idempotency guard: Handles request deduplication
- Mappers: DTO ↔ Domain conversions
- Dependency: Only knows about domain interfaces

**Domain Layer (domain/)**
- Use cases: Business logic and validation rules
- Models: Account, PaymentResult
- Services: Match calculation, due date calculation
- SPIs (Secondary Ports): Persistence and caching abstractions
- Exceptions: Domain-specific error types
- Independent: No Spring/HTTP/DB dependencies

**Infrastructure Layer (infra/)**
- Adapters: Implement domain SPIs using Redis
- Entities: Persistence models (Redis entities)
- Mappers: Entity ↔ Domain conversions
- Repositories: Spring Data for CRUD
- Configuration: Redis template setup

**Bootstrap Layer (bootstrap/)**
- Application entry point
- Module component scanning
- Configuration beans (Clock)
- Wires everything together

### Key Design Patterns

1. **Hexagonal Architecture**: Domain at center, ports (interfaces) define boundaries
2. **Port & Adapter**: Domain defines SPIs, infra provides implementations
3. **Dependency Inversion**: Depend on abstractions, not concretions
4. **Strategy Pattern**: IdempotencyGuard with Supplier<T>
5. **Repository Pattern**: Spring Data Redis with mapper layer
6. **Mapper Pattern**: MapStruct for type-safe conversions
7. **Central Exception Handling**: GlobalExceptionHandler for consistent responses
8. **Centralized Idempotency**: IdempotencyGuard for retry safety
9. **Conditional Beans**: ClockConfig for testable time source

### Data Flow Example: Payment Request

```
1. HTTP POST /one-time-payment
   ↓
2. PaymentController.oneTimePayment()
   ├─ Validate request (@Valid @RequestBody)
   ├─ Add userId to MDC
   ├─ Call IdempotencyGuard.resolve()
   │  ├─ Check RedisIdempotencyStore (cache hit?)
   │  │  └─ If yes: return cached response
   │  │
   │  └─ If no: execute supplier
   │     ├─ Call ProcessPaymentService.process()
   │     │  ├─ Validate payment amount > 0
   │     │  ├─ Load account via AccountAdapter (Redis)
   │     │  ├─ Calculate match via MatchCalculationServiceImpl
   │     │  ├─ Validate funds available
   │     │  ├─ Debit account
   │     │  ├─ Calculate due date via DueDateCalculationServiceImpl
   │     │  ├─ Save account via AccountAdapter (Redis)
   │     │  └─ Return PaymentResult
   │     │
   │     ├─ Map PaymentResult to OneTimePaymentResponse via PaymentResponseMapper
   │     ├─ Store response in RedisIdempotencyStore (24h TTL)
   │     └─ Return response
   │
   ├─ Return ResponseEntity.ok(response)
   └─ Remove userId from MDC (in finally)
   ↓
3. HTTP 200 OK with OneTimePaymentResponse body

If exception thrown:
   ↓
GlobalExceptionHandler catches and maps to appropriate HTTP response:
   - AccountNotFoundException → 404
   - InvalidPaymentAmountException → 400
   - InsufficientBalanceException → 422
   - Other exceptions → 500
```

---

## Conclusion

This codebase demonstrates **enterprise-grade Spring Boot architecture**:

✅ **Clean Architecture**: Domain independent, infrastructure pluggable  
✅ **SOLID Principles**: Single responsibility, dependency inversion  
✅ **Testability**: Mockable dependencies, fixable Clock  
✅ **Maintainability**: Clear concerns, minimal duplication  
✅ **Production-Ready**: Error handling, idempotency, observability (logging)  
✅ **Modern Java**: Records, switch expressions, Optional  
✅ **Type Safety**: MapStruct, generics, compile-time verification  

The payment system showcases how these patterns work together to create a **flexible, resilient, and maintainable application**.

