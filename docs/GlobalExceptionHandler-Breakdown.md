# GlobalExceptionHandler.java - Line by Line Breakdown

## Overview
`GlobalExceptionHandler` is a Spring REST Controller Advice that centralizes exception handling across the entire application. It maps domain-specific exceptions and validation errors to appropriate HTTP responses with structured error payloads. This ensures consistent, predictable error responses across all REST endpoints without duplicating error handling logic in individual controllers.

---

## Package and Imports

### Line 1
```java
package com.customercare.app.handler;
```
- Declares the package structure for this exception handler
- Organized within the `handler` module of the application layer

### Lines 3-17
```java
import com.customercare.domain.exception.AccountNotFoundException;
import com.customercare.domain.exception.InsufficientBalanceException;
import com.customercare.domain.exception.InvalidPaymentAmountException;
import com.customercare.dto.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
```

**Import Details:**

| Import | Purpose |
|--------|---------|
| `AccountNotFoundException` | Domain exception - thrown when an account doesn't exist |
| `InsufficientBalanceException` | Domain exception - thrown when account balance is too low |
| `InvalidPaymentAmountException` | Domain exception - thrown when payment amount is invalid |
| `ErrorResponse` | DTO auto-generated from `openapi.yaml` for structured error responses |
| `@Slf4j` | Lombok annotation that auto-generates a static `log` field |
| `HttpStatus` | Spring enum for HTTP status codes (200, 404, 422, 500, etc.) |
| `ResponseEntity<T>` | Spring class for building HTTP responses with headers and body |
| `@ExceptionHandler` | Spring annotation marking methods that handle specific exceptions |
| `@RestControllerAdvice` | Spring annotation registering this class as a global exception handler |
| `OffsetDateTime` | Java 8+ time API for timezone-aware timestamps |
| `ZoneOffset` | Represents UTC offset for timestamps |
| `List<T>` | Java collection for storing multiple error details |

---

## Class-Level Documentation

### Lines 19-27
```java
/**
 * Centralised exception → HTTP response mapping.
 *
 * <p>Handles domain exceptions ({@link AccountNotFoundException},
 * {@link InvalidPaymentAmountException}, {@link InsufficientBalanceException})
 * and Spring validation/catch-all exceptions.
 * {@link ErrorResponse} is generated from {@code openapi.yaml}.
 */
```

**JavaDoc Summary:**
- **Purpose**: Centralizes all exception-to-HTTP response mapping logic
- **Domain Exceptions Handled**: Three specific business domain exceptions
- **Additional Handling**: Spring validation exceptions and generic exceptions
- **ErrorResponse**: Auto-generated DTO from OpenAPI specification ensures consistency with API contract

---

## Class Declaration

### Lines 28-29
```java
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
```

**Annotation Breakdown:**

| Annotation | Purpose |
|-----------|---------|
| `@Slf4j` | Creates a static `log` field for SLF4J logging |
| `@RestControllerAdvice` | Registers this as a global exception handler for all `@RestController` classes across the entire application; exceptions thrown by any controller are intercepted here |

**Scope:** This handler applies **globally** to all REST endpoints; no need to repeat exception handling in individual controllers.

---

## Exception Handler 1: Account Not Found

### Lines 31-35
```java
@ExceptionHandler(AccountNotFoundException.class)
public ResponseEntity<ErrorResponse> handleAccountNotFound(AccountNotFoundException ex) {
    log.warn("Account not found: {}", ex.getMessage());
    return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage(), null);
}
```

**Breakdown:**

| Element | Explanation |
|---------|------------|
| `@ExceptionHandler(AccountNotFoundException.class)` | Specifies this method handles `AccountNotFoundException` |
| `ResponseEntity<ErrorResponse>` | Returns HTTP response with error payload |
| Parameter `AccountNotFoundException ex` | Catches the thrown exception with message details |
| `log.warn(...)` | Logs at WARN level (expected business error, not critical) |
| `HttpStatus.NOT_FOUND` | Returns **404 Not Found** HTTP status |
| `ex.getMessage()` | Uses the exception's message as error description |
| `null` | No detailed field-level errors (account either exists or doesn't) |

**Use Case:** When a REST endpoint tries to fetch or update an account that doesn't exist.

---

## Exception Handler 2: Invalid Payment Amount

### Lines 37-41
```java
@ExceptionHandler(InvalidPaymentAmountException.class)
public ResponseEntity<ErrorResponse> handleInvalidPaymentAmount(InvalidPaymentAmountException ex) {
    log.warn("Invalid payment amount: {}", ex.getMessage());
    return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), null);
}
```

**Breakdown:**

| Element | Explanation |
|---------|------------|
| `@ExceptionHandler(InvalidPaymentAmountException.class)` | Handles invalid payment amounts |
| `HttpStatus.BAD_REQUEST` | Returns **400 Bad Request** - client sent invalid data |
| Logging level | WARN (expected validation failure, not a system error) |

**Use Case:** When a payment request contains a negative or zero amount, or exceeds limits.

---

## Exception Handler 3: Insufficient Balance

### Lines 43-47
```java
@ExceptionHandler(InsufficientBalanceException.class)
public ResponseEntity<ErrorResponse> handleInsufficientBalance(InsufficientBalanceException ex) {
    log.warn("Insufficient balance: {}", ex.getMessage());
    return buildResponse(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), null);
}
```

**Breakdown:**

| Element | Explanation |
|---------|------------|
| `@ExceptionHandler(InsufficientBalanceException.class)` | Handles insufficient balance errors |
| `HttpStatus.UNPROCESSABLE_ENTITY` | Returns **422 Unprocessable Entity** - semantically valid but can't be processed (business rule violation) |
| Why 422 instead of 400 | The request is well-formed, but the account doesn't have enough balance |

**HTTP Status Justification:**
- **400 Bad Request**: For syntax/format errors (like invalid email)
- **422 Unprocessable Entity**: For semantic/business logic errors (like insufficient funds for a valid transaction)

---

## Exception Handler 4: Validation Errors

### Lines 49-56
```java
@ExceptionHandler(MethodArgumentNotValidException.class)
public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
    List<String> fieldErrors = ex.getBindingResult().getFieldErrors()
            .stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .toList();
    log.warn("Validation failed: {}", fieldErrors);
    return buildResponse(HttpStatus.BAD_REQUEST, "Validation failed", fieldErrors);
}
```

**Breakdown:**

**Line 50:**
```java
List<String> fieldErrors = ex.getBindingResult().getFieldErrors()
```
- `ex.getBindingResult()`: Retrieves the binding result containing all validation errors
- `.getFieldErrors()`: Gets list of field-specific validation errors (e.g., @NotNull, @Min, @Email)

**Lines 51-53:**
```java
.stream()
.map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
.toList();
```
- Converts each field error to a readable string format
- **Example**: `"amount: must be greater than 0"`
- Uses functional streams API for concise transformation

**Line 54:**
```java
log.warn("Validation failed: {}", fieldErrors);
```
- Logs all validation failures for debugging and monitoring

**Line 55:**
```java
return buildResponse(HttpStatus.BAD_REQUEST, "Validation failed", fieldErrors);
```
- Returns 400 Bad Request with generic message
- Passes detailed field errors in the response body

**When Triggered:** Spring automatically throws `MethodArgumentNotValidException` when:
- `@NotNull`, `@NotBlank`, `@Email`, `@Min`, `@Max` validations fail
- Request body validation fails (e.g., `@Valid @RequestBody`)

---

## Exception Handler 5: Catch-All Generic Handler

### Lines 58-62
```java
@ExceptionHandler(Exception.class)
public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
    log.error("Unexpected error", ex);
    return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR,
            "An unexpected error occurred", null);
}
```

**Breakdown:**

| Element | Explanation |
|---------|------------|
| `@ExceptionHandler(Exception.class)` | Catches **any** exception not handled by specific handlers (catch-all) |
| `log.error("Unexpected error", ex)` | Logs at ERROR level with full stack trace (unexpected, needs investigation) |
| `HttpStatus.INTERNAL_SERVER_ERROR` | Returns **500 Internal Server Error** |
| Generic message | Doesn't expose internal error details to clients (security best practice) |
| `null` for errors | No detailed field errors for unexpected failures |

**Purpose:** Safety net to ensure no unhandled exception crashes the application and leaves the client without a response.

---

## Helper Method: Build Response

### Lines 66-75
```java
private ResponseEntity<ErrorResponse> buildResponse(HttpStatus status,
                                                     String message,
                                                     List<String> errors) {
    ErrorResponse body = new ErrorResponse()
            .timestamp(OffsetDateTime.now(ZoneOffset.UTC))
            .status(status.value())
            .error(status.getReasonPhrase())
            .message(message)
            .errors(errors);
    return ResponseEntity.status(status).body(body);
}
```

**Purpose:** Centralizes response building to avoid duplication across all exception handlers.

**Line 66-68 - Method Signature:**
```java
private ResponseEntity<ErrorResponse> buildResponse(HttpStatus status,
                                                     String message,
                                                     List<String> errors)
```
- `private`: Only accessible within this class (encapsulation)
- `ResponseEntity<ErrorResponse>`: Returns HTTP response with error body
- Parameters:
  - `status`: HTTP status code (400, 404, 422, 500, etc.)
  - `message`: User-friendly error message
  - `errors`: Detailed field-level errors (nullable, used for validation failures)

**Lines 69-74 - Build ErrorResponse:**
```java
ErrorResponse body = new ErrorResponse()
        .timestamp(OffsetDateTime.now(ZoneOffset.UTC))
        .status(status.value())
        .error(status.getReasonPhrase())
        .message(message)
        .errors(errors);
```

| Field | Example | Purpose |
|-------|---------|---------|
| `.timestamp()` | `2026-04-25T14:30:45.123Z` | When error occurred (ISO-8601 UTC) |
| `.status()` | `404` | Numeric HTTP status code |
| `.error()` | `"Not Found"` | HTTP status reason phrase |
| `.message()` | `"Account not found"` | User-friendly error description |
| `.errors()` | `["amount: must be > 0"]` | Detailed field-level errors (nullable) |

**Line 75 - Return:**
```java
return ResponseEntity.status(status).body(body);
```
- Sets HTTP response status
- Attaches the `ErrorResponse` as JSON body
- Spring serializes `ErrorResponse` to JSON automatically

---

## Example Error Response (JSON)

### Account Not Found (404)
```json
{
  "timestamp": "2026-04-25T14:30:45.123Z",
  "status": 404,
  "error": "Not Found",
  "message": "Account with ID 123 not found",
  "errors": null
}
```

### Validation Failure (400)
```json
{
  "timestamp": "2026-04-25T14:30:45.123Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "errors": [
    "amount: must be greater than 0",
    "accountId: must not be null"
  ]
}
```

### Unexpected Error (500)
```json
{
  "timestamp": "2026-04-25T14:30:45.123Z",
  "status": 500,
  "error": "Internal Server Error",
  "message": "An unexpected error occurred",
  "errors": null
}
```

---

## Exception Handling Flow

```
HTTP Request
     ↓
[Controller Method Executes]
     ↓
    ┌─────────────────────────────────────┐
    │ Exception is Thrown?                 │
    └─┬───────────────────────────────────┘
      │
      ├─→ AccountNotFoundException
      │        ↓
      │   handleAccountNotFound()
      │        ↓
      │   404 Not Found
      │
      ├─→ InvalidPaymentAmountException
      │        ↓
      │   handleInvalidPaymentAmount()
      │        ↓
      │   400 Bad Request
      │
      ├─→ InsufficientBalanceException
      │        ↓
      │   handleInsufficientBalance()
      │        ↓
      │   422 Unprocessable Entity
      │
      ├─→ MethodArgumentNotValidException
      │        ↓
      │   handleValidation()
      │        ↓
      │   400 Bad Request + field errors
      │
      ├─→ Any Other Exception
      │        ↓
      │   handleGeneric()
      │        ↓
      │   500 Internal Server Error
      │
      ↓
[ErrorResponse Built]
     ↓
[JSON Response Sent to Client]
```

---

## Design Patterns & Principles

### Pattern: **Centralized Exception Handling (Advice Pattern)**
- Uses Spring's `@RestControllerAdvice` to intercept exceptions globally
- Eliminates try-catch blocks from controllers (clean code)
- Single source of truth for error response format

### Design Principles Applied

| Principle | Application |
|-----------|------------|
| **DRY (Don't Repeat Yourself)** | Single `buildResponse()` method used by all handlers |
| **Single Responsibility** | Only handles exception → HTTP mapping; doesn't contain business logic |
| **Separation of Concerns** | Exception handling separated from business logic in controllers |
| **Consistency** | All error responses follow the same `ErrorResponse` structure |
| **Security** | Generic message for 500 errors; doesn't expose stack traces to clients |
| **Fail-Safe** | Catch-all handler ensures every exception gets a response |

---

## Key Features & Benefits

### 1. **Centralized Error Handling**
- ✅ One place to manage all exception → HTTP mappings
- ✅ Consistent error response format across all endpoints

### 2. **Domain-Driven**
- ✅ Maps domain exceptions to appropriate HTTP statuses
- ✅ Reflects business semantics in HTTP responses

### 3. **Validation Integration**
- ✅ Automatically handles Spring validation errors
- ✅ Returns detailed field-level error messages

### 4. **Observable**
- ✅ Logs at appropriate levels (WARN for expected errors, ERROR for unexpected)
- ✅ Includes timestamps and details for debugging

### 5. **Maintainable**
- ✅ Adding new exception types only requires adding a new `@ExceptionHandler` method
- ✅ No changes needed to existing controllers

### 6. **Secure**
- ✅ Generic messages for 500 errors (doesn't expose internals)
- ✅ Can be easily extended to redact sensitive information

---

## HTTP Status Code Semantics

| Status | Exception | When to Use |
|--------|-----------|------------|
| **400 Bad Request** | `InvalidPaymentAmountException`, `MethodArgumentNotValidException` | Client sent malformed/invalid data |
| **404 Not Found** | `AccountNotFoundException` | Resource doesn't exist |
| **422 Unprocessable Entity** | `InsufficientBalanceException` | Request is valid but can't be processed (business rule) |
| **500 Internal Server Error** | `Exception` (catch-all) | Unexpected server error |

---

## Usage in Controllers

### Example 1: Without GlobalExceptionHandler (Verbose)
```java
@PostMapping("/payments")
public ResponseEntity<PaymentResponse> processPayment(@RequestBody PaymentRequest req) {
    try {
        return ResponseEntity.ok(paymentService.process(req));
    } catch (AccountNotFoundException ex) {
        return ResponseEntity.status(404)
                .body(new ErrorResponse().status(404).message(ex.getMessage()));
    } catch (InsufficientBalanceException ex) {
        return ResponseEntity.status(422)
                .body(new ErrorResponse().status(422).message(ex.getMessage()));
    }
    // Repeated for every endpoint!
}
```

### Example 2: With GlobalExceptionHandler (Clean)
```java
@PostMapping("/payments")
public ResponseEntity<PaymentResponse> processPayment(@RequestBody PaymentRequest req) {
    // Exception handling is handled centrally!
    // Throws AccountNotFoundException or InsufficientBalanceException
    return ResponseEntity.ok(paymentService.process(req));
}
```

---

## Extension Points

### Adding a New Exception Type
To handle a new exception, simply add:

```java
@ExceptionHandler(YourCustomException.class)
public ResponseEntity<ErrorResponse> handleYourCustom(YourCustomException ex) {
    log.warn("Custom error: {}", ex.getMessage());
    return buildResponse(HttpStatus.CONFLICT, ex.getMessage(), null);
}
```

No changes needed to controllers!

---

## Summary

`GlobalExceptionHandler` is a critical infrastructure component that:

✅ **Centralizes** exception handling using Spring's `@RestControllerAdvice`  
✅ **Maps** domain exceptions to semantically correct HTTP status codes  
✅ **Handles** Spring validation errors with detailed field information  
✅ **Provides** consistent error response format via `ErrorResponse` DTO  
✅ **Keeps** controllers thin and focused on business logic  
✅ **Ensures** every exception results in a proper HTTP response  
✅ **Logs** appropriately (WARN for expected, ERROR for unexpected)  
✅ **Secures** the API by avoiding exposure of internal error details  

This pattern is a best practice in REST API design and is essential for production-ready applications.

