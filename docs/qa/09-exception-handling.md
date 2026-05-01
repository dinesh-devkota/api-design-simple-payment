# Q&A 09 — Exception Handling & Error Responses

> Covers `GlobalExceptionHandler`, `@RestControllerAdvice`, custom domain exceptions, error envelope design, and HTTP status mapping.

---

## Q1: What is `GlobalExceptionHandler` and why is it centralised?

**A:** `GlobalExceptionHandler` is a single class annotated with `@RestControllerAdvice` that intercepts all exceptions thrown by any `@RestController` in the application and converts them to structured HTTP error responses.

Without it, exceptions would propagate to Spring's default `BasicErrorController`, which returns a less-structured JSON (or even HTML) that varies across Spring Boot versions and is hard for API consumers to parse reliably.

**Why centralised?**
- **Consistency** — every error, from every endpoint, returns the same `ErrorResponse` JSON envelope.
- **Separation of concerns** — controllers don't catch exceptions; the handler does.
- **Single place to change error format** — if the team decides to add a `correlationId` field to errors, only one class changes.

---

## Q2: What is `@RestControllerAdvice` and how does it differ from `@ControllerAdvice`?

**A:**
- `@ControllerAdvice` — intercepts exceptions across all `@Controller` classes. Returns whatever the `@ExceptionHandler` method returns. If the method returns a `String`, it's treated as a view name (MVC templates).
- `@RestControllerAdvice` — same as `@ControllerAdvice` but adds `@ResponseBody` to every `@ExceptionHandler` method, meaning the return value is always serialized to JSON (or the configured content type) rather than resolved as a view name.

**Internally** `@RestControllerAdvice` is just `@ControllerAdvice` + `@ResponseBody`.

In a REST API only returning JSON, `@RestControllerAdvice` is the correct choice.

---

## Q3: What are the five exception handlers and what HTTP status does each produce?

| Handler | Exception | HTTP Status | When triggered |
|---|---|---|---|
| `handleAccountNotFound` | `AccountNotFoundException` | `404 Not Found` | `userId` not in Redis |
| `handleInvalidPaymentAmount` | `InvalidPaymentAmountException` | `400 Bad Request` | `paymentAmount <= 0` in domain |
| `handleInsufficientBalance` | `InsufficientBalanceException` | `422 Unprocessable Entity` | `payment + match > balance` |
| `handleValidation` | `MethodArgumentNotValidException` | `400 Bad Request` | Bean Validation on generated DTO fails |
| `handleGeneric` | `Exception` (catch-all) | `500 Internal Server Error` | Any unexpected error |

**Why 422 for insufficient balance instead of 400?**
400 (Bad Request) means the request is malformed — the syntax or format is wrong. 422 (Unprocessable Entity) means the request is syntactically correct but semantically invalid for the current server state. The payment amount is a valid number; the problem is the account doesn't have enough funds. 422 is more precise.

---

## Q4: What is the `ErrorResponse` structure?

**A:** `ErrorResponse` is generated from `openapi.yaml`:

```json
{
  "timestamp": "2026-04-13T10:15:30Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "errors": ["paymentAmount: paymentAmount must be greater than 0"]
}
```

- `timestamp` — UTC ISO-8601 datetime of the error.
- `status` — HTTP status code (integer, readable in logs without knowing the HTTP header).
- `error` — HTTP reason phrase (human-readable name for the status code).
- `message` — specific description of this error.
- `errors` — array of field-level messages; **only populated for 400 validation errors**; `null` for all other status codes (Jackson omits `null` fields due to `default-property-inclusion: non_null` in `application.yml`).

---

## Q5: Why are custom domain exceptions `RuntimeException` subclasses rather than checked exceptions?

**A:**
```java
public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(String message) { super(message); }
}
```

**Checked exceptions** (extending `Exception`) require every caller to either catch them or declare `throws`. For domain exceptions that propagate all the way to the HTTP layer, this would pollute every intermediate method signature with `throws AccountNotFoundException`.

**Unchecked exceptions** (extending `RuntimeException`) propagate automatically until caught by `GlobalExceptionHandler` without requiring `throws` declarations. This is the standard approach in modern Spring applications.

**Disadvantage:** Unchecked exceptions are invisible in method signatures — a caller has no compile-time indication that an exception might be thrown. This is mitigated by:
- Clear naming conventions (`NotFoundException`, `InvalidXyzException`).
- Comprehensive `@ExceptionHandler` coverage.
- Documentation in the OpenAPI spec (all error status codes are declared).

---

## Q6: Why does `handleValidation` extract field-level errors differently from the others?

**A:** `MethodArgumentNotValidException` is thrown by Spring when Bean Validation fails. Unlike domain exceptions (which have a single message), it carries a `BindingResult` with multiple `FieldError` objects — one per violated constraint:

```java
List<String> fieldErrors = ex.getBindingResult().getFieldErrors()
    .stream()
    .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
    .toList();
```

This produces messages like `["paymentAmount: paymentAmount must be greater than 0", "userId: must not be blank"]` — one per failing field. The format `"field: message"` matches most API consumer expectations and makes the response self-explanatory without documentation.

---

## Q7: Why does `handleGeneric` log at `ERROR` while others log at `WARN`?

**A:**
- `WARN` — expected, recoverable operational conditions: a user typed a bad amount, a user-ID doesn't exist. These are domain errors triggered by client input, not system failures.
- `ERROR` — unexpected failures that represent a bug or infrastructure problem. A `500` means something went wrong that shouldn't have — it always warrants investigation. Logging at `ERROR` ensures it triggers alerts in monitoring systems.

The distinction lets teams set alert thresholds: "page me on `ERROR`, notify on `WARN`."

---

## Q8: Why doesn't `handleGeneric` include the exception message in the HTTP response?

**A:**
```java
return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR,
        "An unexpected error occurred", null); // no ex.getMessage()!
```

Exposing exception messages in 500 responses is a **security vulnerability** — internal class names, file paths, SQL queries, or business data can leak to external clients. The full stack trace is logged server-side (where only authorized engineers can see it) via `log.error("Unexpected error", ex)`.

---

## Q9: What are the advantages and disadvantages of this error handling approach?

**Advantages:**
| Advantage | Detail |
|---|---|
| Consistent error shape | All errors use the same `ErrorResponse` envelope |
| No controller boilerplate | Controllers never catch exceptions |
| Security | Internal details never leak to clients |
| Typed handlers | Each exception type has a precise HTTP status |

**Disadvantages:**
| Disadvantage | Detail |
|---|---|
| Catch-all is too broad | `handleGeneric` catches `Exception` — it will also catch things like `OutOfMemoryError` (though `Error` types don't extend `Exception`) or `NullPointerException` |
| No problem details (RFC 7807) | The format is custom; `application/problem+json` (RFC 7807) is a standard error format increasingly adopted by REST APIs |
| No request ID in error | Without a `correlationId` in the error response, clients can't link an error to a specific server log entry |

---

## Q10: What improvements could be made?

1. **RFC 7807 Problem Details** — use `application/problem+json` format (supported by Spring 6 via `ProblemDetail`):
   ```java
   ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
   problem.setType(URI.create("https://api.paytient.com/problems/account-not-found"));
   ```

2. **Correlation ID** — add a `traceId` or `correlationId` field to `ErrorResponse` (drawn from MDC or a `X-Correlation-ID` header) so clients can reference a specific log entry when reporting issues.

3. **Narrower catch-all** — use `handleGeneric(RuntimeException ex)` instead of `Exception` so checked exceptions (if any are introduced) are not silently converted to 500s.

4. **Validation error codes** — return machine-readable error codes (e.g., `AMOUNT_TOO_LOW`) alongside human messages so clients can programmatically react.

5. **Localisation** — externalize error messages to `messages.properties` for i18n support, using `MessageSource` to resolve locale-specific strings.
