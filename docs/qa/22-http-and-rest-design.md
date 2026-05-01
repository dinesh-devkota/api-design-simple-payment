# Q&A 22 — HTTP, REST, and API Design: The Hardest Interview Questions

> Status codes, idempotency, REST constraints, versioning, error design, content negotiation — everything a senior engineer must know cold.

---

## Q1: Why is `POST /one-time-payment` not idempotent by default, and what does the `Idempotency-Key` header add?

**A:** HTTP's `POST` method is **not inherently idempotent** — the spec says repeating the same `POST` may have additional effects (it creates or triggers something). `GET`, `PUT`, `DELETE`, and `HEAD` are defined as idempotent in the HTTP spec.

This endpoint processes a payment — a financial transaction. Sending the same `POST` twice without a key would deduct the balance twice.

The `Idempotency-Key` header **makes the operation idempotent at the application level**: the server caches the response by key, and any replay returns the cached result without re-processing. This is a pattern used by Stripe, Plaid, and most payment APIs.

**The key is the client's responsibility** — the client generates a unique key (UUID) per logical operation and reuses it on retries. The server stores responses for 24 hours.

---

## Q2: Explain every HTTP status code used in this project and why each was chosen.

**A:**

| Status | Reason phrase | When used | Why this code |
|---|---|---|---|
| `200 OK` | OK | Successful payment | Standard success for a `POST` that returns data |
| `400 Bad Request` | Bad Request | Zero/negative amount, missing userId | Request is syntactically or semantically invalid regardless of server state |
| `404 Not Found` | Not Found | `userId` not in Redis | The requested resource (account) does not exist |
| `422 Unprocessable Entity` | Unprocessable Entity | Payment + match > balance | Request is valid but cannot be processed given current state |
| `500 Internal Server Error` | Internal Server Error | Unexpected exception | Catch-all for bugs and infrastructure failures |

**Why not `400` for insufficient balance?**
`400` says "the request itself is malformed." The request is perfectly formed — the userId and amount are valid. The problem is the *server's current state* (the account balance). `422` precisely means: "I understand the request and it is well-formed, but I cannot process it."

**Why not `201 Created` for a successful payment?**
`201 Created` is for requests that create a new addressable resource at a `Location` URL. This payment doesn't create a resource the client can `GET` later — it processes a transaction and returns the result inline. `200 OK` is correct.

---

## Q3: What are the REST constraints (Richardson Maturity Model) and where does this API sit?

**A:** The Richardson Maturity Model defines four levels of REST maturity:

| Level | Name | What it means | This API |
|---|---|---|---|
| 0 | Swamp of POX | One endpoint, everything tunneled through it | ✗ |
| 1 | Resources | Multiple endpoints, each representing a resource | Partial ✓ |
| 2 | HTTP Verbs | Uses HTTP methods correctly (GET for read, POST for create/action) | ✓ |
| 3 | Hypermedia (HATEOAS) | Responses include `_links` to related actions/resources | ✗ |

This API is **Level 2**. It uses:
- Meaningful HTTP verbs (`POST` for payment).
- Correct status codes.
- Structured request/response bodies.

It does not implement HATEOAS (Level 3) — no `_links` in the response pointing to related endpoints. For a payment API, Level 2 is standard and appropriate.

---

## Q4: What is the difference between `application/json` and `application/problem+json`?

**A:**
- `application/json` — generic JSON content type. No implied structure. Used for the success response (`OneTimePaymentResponse`).
- `application/problem+json` — RFC 7807 standard for error responses. Defines a standard set of fields: `type`, `title`, `status`, `detail`, `instance`. APIs that return this content type for errors are interoperable with any RFC 7807-aware client or monitoring tool.

This project uses a custom error envelope (`ErrorResponse` with `timestamp`, `status`, `error`, `message`, `errors`) rather than RFC 7807. The content type is still `application/json` for errors.

**Improvement:** Migrate the error format to RFC 7807, supported natively in Spring 6 via `ProblemDetail`:
```java
ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
problem.setType(URI.create("https://api.paytient.com/errors/account-not-found"));
return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
```

---

## Q5: What is content negotiation and does this API support it?

**A:** Content negotiation is the mechanism by which a client and server agree on the format of the response. The client specifies preferences via the `Accept` header:

```
Accept: application/json          → want JSON
Accept: application/xml           → want XML
Accept: application/json, */*;q=0 → JSON only, reject everything else
```

This API **only supports `application/json`**. If a client sends `Accept: application/xml`, Spring returns `406 Not Acceptable` because no XML serializer (JAXB) is configured. Jackson is the only registered `HttpMessageConverter` for response bodies.

For enterprise integrations requiring XML, add `jackson-dataformat-xml` or JAXB to the classpath. Spring MVC will automatically negotiate based on the `Accept` header.

---

## Q6: Why is `paymentAmount` passed in the request body instead of a path parameter or query string?

**A:** Path parameters (`/one-time-payment/10.00`) and query strings (`/one-time-payment?amount=10.00`) have limitations for payment data:
- Path parameters are part of the URL, which is logged by proxies, CDNs, and access logs — **financial amounts in URLs are an information disclosure risk**.
- Query strings are also visible in URLs and browser history.
- HTTP specs suggest request bodies for state-changing operations, and the `POST` method is designed for this.
- The request body is encrypted with TLS along with the entire HTTP message body — query strings are also encrypted with TLS but are more often logged.

Additionally, `POST` with a body allows the full `OneTimePaymentRequest` DTO with Bean Validation — multiple fields validated together in one structured object.

---

## Q7: What is the `Idempotency-Key` header's scope — what should a client use as the key?

**A:** The key must be:
1. **Unique per logical operation** — not per request. If a client is retrying a failed payment, it must use the *same* key, not a new one.
2. **Client-generated** — the server does not generate keys (that would defeat the purpose — the client needs the key before the first request, not after).
3. **Opaque to the server** — the server treats it as a string. Common choices: UUID v4, a hash of the operation parameters.

**Recommended client pattern:**
```
key = UUID.randomUUID().toString()  // generated once, before the first attempt
// On retry: reuse the SAME key
```

**Do not use:**
- Incrementing integers — collisions across users.
- Timestamps — two requests in the same millisecond collide; retries within the TTL window use a different timestamp.
- Hash of the request body — two *different* users submitting identical payment amounts would share a key.

---

## Q8: What is the difference between `@RequestBody`, `@RequestParam`, and `@PathVariable`?

**A:**

| Annotation | Reads from | Use case |
|---|---|---|
| `@RequestBody` | HTTP request body (JSON, XML) | Complex objects, POST/PUT data |
| `@RequestParam` | Query string (`?key=value`) | Simple filters, search parameters |
| `@PathVariable` | URL path segment (`/users/{id}`) | Resource identifiers in RESTful URLs |

In this project, `@RequestBody` reads the JSON `OneTimePaymentRequest`. The `Idempotency-Key` header is read via the generated `PaymentApi` interface's `@RequestHeader` parameter (generated from the OpenAPI spec).

---

## Q9: Why does the OpenAPI spec include example values in `requestBody`?

**A:**
```yaml
examples:
  mid-tier:
    summary: "Mid-tier match — 3% ($10 payment) — user-001 seeded with $100.00"
    value:
      userId: "user-001"
      paymentAmount: 10.00
```

Swagger UI renders these examples as pre-filled form values — a developer opening `http://localhost:8080/swagger-ui.html` can immediately execute a working request without reading documentation or knowing what data to use.

The examples are also:
- Pre-aligned with the seeded Redis data (`seed-local-data.sh`) — `user-001` is guaranteed to have `$100.00` when the seed script is run.
- Documenting expected outcomes (`user-001 seeded with $100.00`) — the comment serves as living documentation.

---

## Q10: What would API versioning look like for this service and when should it be introduced?

**A:** Three common strategies:

### 1. URL path versioning
```
POST /v1/one-time-payment
POST /v2/one-time-payment
```
Pros: Explicit, easy to route, visible in logs. Cons: Pollutes URLs with non-resource concerns.

### 2. Header versioning
```
Accept: application/vnd.paytient.v2+json
```
Pros: Clean URLs. Cons: Harder to test in a browser, less visible.

### 3. Query parameter versioning
```
POST /one-time-payment?version=2
```
Pros: Simple. Cons: Query params conventionally represent filters, not versions.

**When to introduce versioning:**
- Before any breaking change: removing a field, changing a field type, changing the URL structure.
- **Not** for additive changes: adding a new optional response field is backward-compatible — no version bump needed.

**Recommended approach for this project:** URL path versioning (`/v1/`) introduced at the point the first breaking change is required. The OpenAPI spec already has `version: 1.0.0-SNAPSHOT` in the `info` block — moving to `/v1/` is a natural next step when Iteration 2 (Oracle-backed) introduces a different response structure.
