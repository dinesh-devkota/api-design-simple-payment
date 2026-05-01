# Q&A 05 — `ProcessPaymentService`: The Core Use-Case Orchestrator

> Covers what this class does, why it's the heart of the domain, validation order, and improvements.

---

## Q1: What is `ProcessPaymentService` and why is it the most important class?

**A:** `ProcessPaymentService` is the **use-case implementation** — the single place where all domain business rules are coordinated for a one-time payment:

1. Validate that the payment amount is positive.
2. Fetch the account.
3. Calculate the tier match percentage and dollar amount.
4. Validate that the total deduction does not exceed the balance.
5. Calculate the new balance and due date.
6. Persist the updated account.
7. Return a `PaymentResult` to the caller.

It is the most important class because **removing it would leave business logic scattered** across the controller, adapter, and other places — the classic "anemic domain model" anti-pattern.

---

## Q2: Walk through `process()` line by line.

**A:**
```java
public PaymentResult process(String userId, BigDecimal paymentAmount) {

    // 1. Guard: reject non-positive amounts immediately, before any I/O
    if (paymentAmount.compareTo(BigDecimal.ZERO) <= 0) {
        throw new InvalidPaymentAmountException("...");
    }

    // 2. Fetch account; throw a domain exception if not found
    Account account = accountSpi.findById(userId)
            .orElseThrow(() -> new AccountNotFoundException("..."));

    // 3. Capture previous balance before any mutation
    BigDecimal previousBalance = account.getBalance();

    // 4. Delegate match tier logic to MatchCalculationService
    int        matchPercentage = matchCalculationService.getMatchPercentage(paymentAmount);
    BigDecimal matchAmount     = matchCalculationService.calculateMatchAmount(paymentAmount);

    // 5. Total deduction = payment + match
    BigDecimal totalDeduction = paymentAmount.add(matchAmount);

    // 6. Guard: reject if deduction exceeds balance
    if (totalDeduction.compareTo(previousBalance) > 0) {
        throw new InsufficientBalanceException("...");
    }

    // 7. Compute new balance, rounded to 2dp HALF_UP
    BigDecimal newBalance = previousBalance
                                .subtract(totalDeduction)
                                .setScale(2, RoundingMode.HALF_UP);

    // 8. Resolve "today" via injected Clock (deterministic in tests)
    LocalDate today       = LocalDate.now(clock);
    LocalDate nextDueDate = dueDateCalculationService.calculateDueDate(today);

    // 9. Mutate and persist
    account.setBalance(newBalance);
    accountSpi.save(account);

    // 10. Structured log (no stack trace, no sensitive data beyond what's approved)
    log.info("Payment processed: userId={} ...", ...);

    // 11. Return immutable result record
    return new PaymentResult(...);
}
```

---

## Q3: Why is amount validation done *before* the account lookup?

**A:** **Fail fast with least I/O.** A zero or negative amount is a client error detectable without touching Redis. By validating first:
- No unnecessary network round-trip to Redis for clearly invalid requests.
- The error is returned with the correct HTTP 400 status before any state is read.
- Bean Validation on the DTO (enforced by the generated OpenAPI class) catches most of these at the HTTP layer before the use-case is even called — but the domain validates again for defence in depth.

---

## Q4: Why does the use-case also validate the amount when the OpenAPI spec already enforces `minimum: 0.01`?

**A:** **Defence in depth** — the domain should not trust its callers.

- The controller is one caller today; a CLI tool, message consumer, or batch job could be another caller tomorrow.
- If the domain relied solely on the HTTP layer's Bean Validation, any non-HTTP entry point would bypass the check.
- Domain invariants should be enforced at the domain boundary regardless of which adapter calls them.

---

## Q5: Why is `previousBalance` captured before calling `setBalance`?

**A:**
```java
BigDecimal previousBalance = account.getBalance(); // snapshot before mutation
// ...
account.setBalance(newBalance); // mutation happens here
return new PaymentResult(userId, previousBalance, ...); // uses snapshot
```

If `previousBalance` were read *after* `setBalance`, it would return the *new* balance, producing an incorrect response where `previousBalance == newBalance`. Capturing it first is a simple ordering discipline.

---

## Q6: Why does the service both log a structured message *and* return a `PaymentResult`?

**A:** They serve different consumers:
- **The log** is for operational teams — it is emitted to a log aggregator (e.g., Splunk, ELK) and enables querying "show me all payments for user-001 today."
- **The `PaymentResult`** is for the HTTP response — it carries exactly the data the client needs, no more.

The log line contains `matchPercentage` and `matchAmount` (internal data not exposed in the API response), which is useful for debugging but intentionally hidden from the client.

---

## Q7: What exceptions does this service throw, and what HTTP status do they map to?

| Exception | Condition | HTTP Status |
|---|---|---|
| `InvalidPaymentAmountException` | `paymentAmount <= 0` | `400 Bad Request` |
| `AccountNotFoundException` | `userId` not in Redis | `404 Not Found` |
| `InsufficientBalanceException` | `payment + match > balance` | `422 Unprocessable Entity` |

The mapping is done in `GlobalExceptionHandler` in the `app` module — the domain exceptions are pure domain objects with no HTTP imports.

---

## Q8: Why does `accountSpi.save()` happen *before* the `PaymentResult` is returned, not after?

**A:** The account balance must be committed to Redis before the response is returned to the caller. If the save happened after returning, a concurrent second request arriving between the return and the save would see the old balance and allow double-spending.

---

## Q9: What are the advantages and disadvantages of this design?

**Advantages:**
| Advantage | Detail |
|---|---|
| All business rules in one place | Easy to audit and reason about |
| No framework imports | Pure Java — no Spring, no Redis in this class |
| Structured, ordered operations | Clear sequence: validate → fetch → calculate → check → persist → respond |
| Injected `Clock` | Deterministic for tests |

**Disadvantages / trade-offs:**
| Disadvantage | Detail |
|---|---|
| Account is mutable | `account.setBalance(newBalance)` mutates the object; a missed save would leave the domain object in an inconsistent state |
| No transaction | Redis is not ACID; there's no atomic compare-and-swap around fetch + save |
| No retry logic | If `accountSpi.save()` fails after the calculation, the payment is lost |

---

## Q10: What improvements could be made?

1. **Immutable Account** — replace `setBalance` with `Account updated = new Account(account.getUserId(), newBalance)` to avoid mutating shared state.
2. **Atomic update** — use a Redis Lua script or WATCH/MULTI/EXEC to atomically check-and-update the balance, preventing race conditions under concurrent requests for the same user.
3. **Outbox pattern** — persist a `PaymentEvent` in the same atomic Redis transaction as the balance update. A background processor then emits the event to a message bus, enabling eventual-consistency integration with downstream systems.
4. **Return `Either<DomainError, PaymentResult>`** — functional-style error handling avoids exceptions for expected domain failures, making the use-case's contract more explicit without relying on catch blocks up the stack.
5. **Domain event publication** — after a successful save, publish a `PaymentProcessedEvent` to allow other domain services (notifications, audit log) to react without coupling to this service.
