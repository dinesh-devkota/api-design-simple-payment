# Q&A 06 — REST Layer & Contract-First API Design

> Covers `PaymentController`, `HelloController`, OpenAPI, code generation, MDC logging, and `PaymentResponseMapper`.

---

## Q1: What does `PaymentController` do and why is it described as "thin"?

**A:** `PaymentController` is a **primary adapter** — it translates HTTP requests into domain calls and maps the result back to HTTP responses. Its only responsibilities are:

1. Accept and validate the HTTP request (delegated to Bean Validation).
2. Set the MDC correlation field (`userId`).
3. Delegate to `IdempotencyGuard` (which may short-circuit to a cached response).
4. Call the `ProcessPaymentUseCase`.
5. Map the domain result to a response DTO.
6. Log the outcome.
7. Return `ResponseEntity.ok(response)`.

It contains **no business logic** — no tier calculations, no date arithmetic, no balance checks. "Thin" means it has no reason to change unless the HTTP contract changes.

---

## Q2: What is contract-first API design and why is it used here?

**A:** Contract-first means the **OpenAPI YAML (`openapi.yaml`) is written before any Java code**. A Maven plugin (`openapi-generator-maven-plugin`) then generates:
- `OneTimePaymentRequest` — the request DTO with Bean Validation annotations.
- `OneTimePaymentResponse` — the response DTO.
- `ErrorResponse` — the standard error envelope.
- `PaymentApi` — a Java interface with the method signature and all Spring MVC annotations.

`PaymentController` simply `implements PaymentApi`:
```java
public class PaymentController implements PaymentApi {
    @Override
    public ResponseEntity<OneTimePaymentResponse> oneTimePayment(...) { ... }
}
```

**Why contract-first?**
- The API spec is the single source of truth — consumers, backend, and documentation are always in sync.
- Swagger UI is auto-generated from the same YAML, populated with working examples.
- Constraints (e.g., `minimum: 0.01` on `paymentAmount`) are defined once in YAML and enforced automatically by the generated Bean Validation annotations.
- Prevents accidental breaking changes — if a response field is removed from the YAML, the generated DTO no longer has it, causing compile errors immediately.

---

## Q3: What is MDC and why is `userId` placed in it?

**A:** MDC (Mapped Diagnostic Context) is a per-thread key-value map managed by the Logback/SLF4J logging framework. Any value placed in it is automatically included in every log line emitted by that thread until it is cleared.

```java
MDC.put("userId", request.getUserId()); // before processing
try {
    // ... all log lines inside here include userId automatically
} finally {
    MDC.remove("userId");               // always clean up
}
```

**Why `finally`?** The `MDC.remove` is in a `finally` block to guarantee cleanup even if an exception is thrown. Failing to clear MDC in a thread-pool environment would leak a previous user's ID into the next request handled by the same thread.

**How to use it:** Add `%X{userId}` to the Logback pattern:
```xml
<pattern>%d{ISO8601} %-5level [%X{userId}] %logger{36} - %msg%n</pattern>
```
Then a single `grep "userId=user-001"` in a log aggregator pulls the complete trace for one user's request.

---

## Q4: What does `PaymentResponseMapper` do and why use MapStruct?

**A:**
```java
@Mapper(componentModel = "spring")
public interface PaymentResponseMapper {
    OneTimePaymentResponse toResponse(PaymentResult result);
}
```

MapStruct generates an implementation class (`PaymentResponseMapperImpl`) at compile time that maps each field from `PaymentResult` to `OneTimePaymentResponse`. Because both objects have matching field names (`previousBalance`, `newBalance`, `nextPaymentDueDate`, `paymentDate`), no `@Mapping` annotations are needed.

**Why MapStruct instead of manual mapping?**
- Zero-boilerplate — no hand-written `response.setNewBalance(result.newBalance())` lines.
- Compile-time safety — if a field is added to `PaymentResult` but not to `OneTimePaymentResponse`, MapStruct warns or errors at build time.
- No reflection — the generated code is plain Java getter/setter calls, so it's as fast as manual mapping.

**What fields are *not* mapped?** `matchPercentage`, `matchAmount`, `userId`, and `paymentAmount` exist in `PaymentResult` but not in `OneTimePaymentResponse`. MapStruct silently ignores unmapped source fields (configurable to warn or error). This is intentional — internal calculation details are not exposed to the API consumer.

---

## Q5: Why does `PaymentController` use `ResponseEntity.ok()` instead of just returning the DTO?

**A:** `ResponseEntity` gives explicit control over the HTTP status code and headers. Using `ResponseEntity.ok(body)` makes the intent clear: "this is a 200 OK with this body." If a future version needed to return `201 Created` or add response headers (e.g., `Location`), changing `ResponseEntity.ok()` to the appropriate method is a one-line change.

Alternatively, the method return type could be just `OneTimePaymentResponse` with `@ResponseStatus(HttpStatus.OK)`, but `ResponseEntity` is more explicit and the generated `PaymentApi` interface already declares it.

---

## Q6: Why is there a `HelloController` (`GET /hello`)?

**A:** It is a **smoke-test endpoint** — a minimal endpoint that confirms the service started, wired its Spring context, and is reachable over HTTP. It:
- Returns HTTP 200 with the string `"Hello from customer-care-api!"`.
- Can be polled by a load balancer or health-check monitor.
- Enables a one-line sanity check after deployment: `curl http://localhost:8080/hello`.

**Why not use `/actuator/health` (Spring Boot Actuator)?** Actuator health is also available, but `GET /hello` is a custom, lightweight endpoint that requires no extra dependency and is immediately visible in Swagger UI under the "Health" tag.

---

## Q7: What are the advantages and disadvantages of generating DTOs from OpenAPI?

**Advantages:**
| Advantage | Detail |
|---|---|
| Single source of truth | YAML drives Java, Swagger, and client SDKs |
| Consistency | Bean Validation constraints are not hand-coded — they come from the spec |
| Breaking-change detection | Removing a field in YAML fails compilation immediately |
| Easy SDK generation | Frontend or integration partners can generate typed clients from the same YAML |

**Disadvantages:**
| Disadvantage | Detail |
|---|---|
| Generated code must not be edited | Developers must remember not to hand-edit generated files |
| Build step required | `mvn generate-sources` must run before the IDE sees the generated classes |
| Plugin configuration complexity | `openapi-generator-maven-plugin` has many options; misconfiguration can produce broken code |
| Less control over DTO shape | Custom validation logic or special Jackson annotations require generator config or wrapper classes |

---

## Q8: What improvements could be made to the REST layer?

1. **Response pagination** — if a `GET /payments` history endpoint is added, add `page`/`size` query parameters and a `Page<PaymentSummary>` response.
2. **Rate limiting** — protect `POST /one-time-payment` with token-bucket rate limiting (e.g., via Spring Cloud Gateway or a `HandlerInterceptor`).
3. **HATEOAS** — add `_links` to the response (e.g., a link to the user's balance or payment history) to make the API self-discoverable.
4. **Versioning** — prefix paths with `/v1/` or use `Accept: application/vnd.customercare.v1+json` headers to allow non-breaking evolution.
5. **Request tracing header** — propagate a `X-Correlation-ID` header from incoming requests into the MDC alongside `userId`, enabling cross-service request tracing.
6. **Content negotiation** — currently only JSON is supported; adding `Accept: application/xml` support for enterprise clients would require a JAXB dependency and schema work.
