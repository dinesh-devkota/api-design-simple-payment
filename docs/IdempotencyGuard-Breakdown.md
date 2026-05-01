# IdempotencyGuard.java - Line by Line Breakdown

## Overview
`IdempotencyGuard` is a Spring component that encapsulates the idempotency cache-check / execute / store pattern. It ensures that HTTP controllers remain thin adapters without conditional idempotency logic, while guaranteeing that duplicate requests with the same idempotency key return the same cached response without re-executing the work.

---

## Package and Imports

### Line 1
```java
package com.customercare.app.idempotency;
```
- Declares the package structure for this class
- Organizes the class within the idempotency module of the application

### Lines 3-8
```java
import com.customercare.domain.spi.IdempotencyStoreSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.Supplier;
```

**Import Details:**
- `IdempotencyStoreSpi`: A domain-layer interface (SPI = Service Provider Interface) that abstracts idempotency storage operations
- `@RequiredArgsConstructor`: Lombok annotation that auto-generates a constructor accepting all `final` fields as parameters
- `@Slf4j`: Lombok annotation that auto-generates a static logger instance named `log`
- `@Component`: Spring annotation to register this class as a managed bean in the application context
- `Optional<T>`: Java utility for representing optional values (used for cache lookups)
- `Supplier<T>`: Functional interface for lazy execution of the actual business logic

---

## Class-Level Documentation

### Lines 12-25
```java
/**
 * Encapsulates the idempotency cache-check / execute / store pattern so that
 * controllers remain thin HTTP adapters with no conditional idempotency logic.
 *
 * <p>Usage:
 * <pre>{@code
 * Response r = idempotencyGuard.resolve(idempotencyKey, Response.class,
 *         () -> doTheActualWork());
 * }</pre>
 *
 * <p>If {@code key} is blank or null the supplier is called directly with no
 * caching.  If a cached entry exists it is returned immediately (cache hit
 * logged at INFO).  Otherwise the supplier is executed and its result is
 * stored before being returned.
 */
```

**JavaDoc Explanation:**
- **Purpose**: Abstracts idempotency logic to keep controllers clean and focused on HTTP concerns
- **Usage Pattern**: Shows how to use the class with a practical example
- **Behavior Summary**:
  - Null or blank keys bypass caching entirely
  - Cache hits return immediately with INFO-level logging
  - Cache misses execute the supplier, store the result, and return it

---

## Class Declaration

### Lines 26-29
```java
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyGuard {
```

**Annotation Breakdown:**
- `@Slf4j`: Creates a static `log` field using SLF4J (Simple Logging Facade for Java)
- `@Component`: Registers this class as a Spring singleton bean, making it injectable as a dependency
- `@RequiredArgsConstructor`: Generates a constructor that accepts `idempotencyStore` as a parameter and assigns it to the field

---

## Class Field

### Line 31
```java
private final IdempotencyStoreSpi idempotencyStore;
```

**Field Details:**
- `private final`: Ensures immutability and thread-safety; cannot be reassigned after construction
- `IdempotencyStoreSpi`: The abstraction for reading/writing cached responses
- Auto-injected by Spring via the generated constructor
- Follows Dependency Inversion Principle by depending on an interface/SPI rather than concrete implementations

---

## Method Documentation

### Lines 33-44
```java
/**
 * Resolves a response idempotently.
 *
 * @param key      the {@code Idempotency-Key} header value; may be null or blank
 * @param type     the expected response type (used for deserialization)
 * @param supplier called only on a cache miss or when {@code key} is absent
 * @param <T>      response type
 * @return cached or freshly computed response
 */
```

**JavaDoc Parameters:**
- `key`: The idempotency key from the request (typically from the `Idempotency-Key` HTTP header)
- `type`: The response class type, needed to deserialize cached objects (e.g., `PaymentResponse.class`)
- `supplier`: A lambda or function reference that performs the actual work; only invoked on cache miss
- `<T>`: Generic type parameter allowing any response type

**Return Value:**
- Returns either a cached response (if key exists and cache hit) or a freshly computed response (on cache miss or no key)

---

## Method Signature

### Line 45
```java
public <T> T resolve(String key, Class<T> type, Supplier<T> supplier) {
```

**Breakdown:**
- `public`: Accessible throughout the application; typically called from HTTP controllers
- `<T>`: Generic type parameter making this method work with any response type
- `String key`: The idempotency key (nullable, can be blank)
- `Class<T> type`: The class type object for the generic response type
- `Supplier<T>`: A functional interface that provides a `get()` method returning a result of type `T`
- Returns: `<T>` - the final response (cached or freshly computed)

---

## Method Body - Phase 1: Bypass Caching

### Lines 47-49
```java
if (key == null || key.isBlank()) {
    return supplier.get();
}
```

**Logic:**
- **Condition**: Checks if the key is null or consists only of whitespace (`isBlank()`)
- **Action**: If true, immediately invoke the supplier without any caching
- **Purpose**: Allows non-idempotent operations to bypass the caching mechanism entirely
- **Why**: Some operations may not require idempotency; forcing caching would be incorrect

---

## Method Body - Phase 2: Cache Lookup

### Lines 51-55
```java
Optional<T> cached = idempotencyStore.find(key, type);
if (cached.isPresent()) {
    log.info("Idempotency cache hit: key={}", key);
    return cached.get();
}
```

**Line 51 - Cache Query:**
- `idempotencyStore.find(key, type)`: Queries the backing store (likely Redis) for a cached result
- Returns an `Optional<T>` - either containing the cached value or empty

**Lines 52-55 - Cache Hit Handling:**
- `cached.isPresent()`: Returns `true` if a cached entry exists
- `log.info(...)`: Logs at INFO level for observability (useful for monitoring cache behavior)
- `cached.get()`: Unwraps and returns the cached value immediately
- **Impact**: Avoids re-executing expensive operations (database queries, API calls, calculations)

---

## Method Body - Phase 3: Cache Miss & Storage

### Lines 57-59
```java
T result = supplier.get();
idempotencyStore.store(key, result);
return result;
```

**Line 57 - Execute Logic:**
- `supplier.get()`: Invokes the actual business logic (only happens on cache miss)
- Executes the operation and stores the result

**Line 58 - Persist Result:**
- `idempotencyStore.store(key, result)`: Caches the result for future requests with the same key
- Enables subsequent identical requests to return the cached response

**Line 59 - Return:**
- Returns the freshly computed result to the caller

---

## Execution Flow Diagram

```
┌─────────────────────────────────────────────────┐
│ resolve(key, type, supplier) called             │
└────────────────────┬────────────────────────────┘
                     │
              ┌──────v──────┐
              │ key valid?  │
              └───┬───────┬─┘
                  │       │
        No        │       │ Yes
                  │       │
           ┌──────v─┐     └──────────┐
           │ Execute│              ┌─v────────────┐
           │ supplier
   (bypass │              │ Cache lookup  │
           │ cache)       └─┬────────┬────┘
           │                │        │
           │        Hit     │        │ Miss
           │                │        │
           │           ┌────v─┐  ┌──v──────────┐
           │           │Return│  │ Execute     │
           │           │cached│  │ supplier    │
           │           │value │  └────┬────────┘
           │           │& log │       │
           │           └────┬─┘       │
           │                │         │
           │              ┌─v─────────v─┐
           │              │Store in cache│
           │              └────┬────────┘
           │                   │
           └───┬────────────┬──┘
               │            │
             ┌─v────────────v─┐
             │Return response  │
             └─────────────────┘
```

---

## Design Pattern & Architecture

### Pattern: **Template Method + Strategy**
- **Template Method**: The `resolve()` method defines the algorithm structure (check → execute → store)
- **Strategy**: The `supplier` is the strategy that varies (different business logic, different operations)

### Design Principles Applied

| Principle | Application |
|-----------|-------------|
| **Single Responsibility** | Only responsible for idempotency logic; delegates actual work to supplier |
| **Dependency Inversion** | Depends on `IdempotencyStoreSpi` (abstraction) not concrete store implementations |
| **Open/Closed** | Open for extension (new store implementations); closed for modification |
| **Clean Code** | Controllers remain thin; idempotency logic extracted to dedicated component |
| **Strategy Pattern** | `Supplier<T>` represents the strategy for what work to perform |

---

## Key Features & Benefits

### 1. **Automatic Caching**
- Eliminates need for conditional idempotency checks in controllers
- Reduces code duplication across endpoints

### 2. **Type-Safe**
- Generic `<T>` ensures compile-time type safety
- `Class<T>` parameter enables proper deserialization from cache

### 3. **Flexible**
- Works with any response type
- Supports optional idempotency (null/blank keys bypass caching)
- Integrates with any `IdempotencyStoreSpi` implementation

### 4. **Observable**
- Logs cache hits at INFO level
- Enables monitoring of idempotency effectiveness

### 5. **Thread-Safe**
- `final` field ensures immutability
- Delegates thread-safety to the `IdempotencyStoreSpi` implementation

---

## Usage Example

```java
// In a Spring REST controller
@PostMapping("/payments")
public ResponseEntity<PaymentResponse> processPayment(
        @RequestHeader(value = "Idempotency-Key") String idempotencyKey,
        @RequestBody PaymentRequest request) {
    
    PaymentResponse response = idempotencyGuard.resolve(
            idempotencyKey,
            PaymentResponse.class,
            () -> paymentService.processPayment(request)
    );
    
    return ResponseEntity.ok(response);
}
```

**How it works:**
1. Client sends request with `Idempotency-Key: abc-123`
2. **First call**: Cache miss → executes `paymentService.processPayment()` → stores result → returns response
3. **Retry with same key**: Cache hit → returns stored response immediately (logging the hit)

---

## Potential Considerations

### TTL (Time-To-Live)
- Current implementation doesn't show explicit TTL handling
- Real implementations typically expire cached entries after a timeout period (handled by `IdempotencyStoreSpi`)

### Error Handling
- Current code doesn't explicitly handle exceptions from the supplier
- Errors from `supplier.get()` would propagate and not be cached (correct behavior)

### Concurrency
- If two identical requests arrive simultaneously (before first finishes), both may execute
- Solutions typically involve distributed locks in the store implementation

### Cache Size & Key Space
- Depends on the `IdempotencyStoreSpi` implementation (typically Redis)
- Should have memory limits and eviction policies

---

## Summary

`IdempotencyGuard` is a sophisticated yet simple component that:
- ✅ Encapsulates idempotency logic away from HTTP controllers
- ✅ Provides a reusable pattern for all endpoints requiring idempotency
- ✅ Maintains type safety with generics
- ✅ Follows SOLID principles and clean architecture
- ✅ Enables clean, testable code in the presentation layer

