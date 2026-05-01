# Q&A 02 — Domain Model: `Account`, `PaymentResult`, and Why `BigDecimal`

> Covers the core data structures, design decisions, and trade-offs.

---

## Q1: What is the `Account` class and why does it exist?

**A:** `Account` is the central **domain model** (in DDD terms, an *Entity*). It represents a customer's payment account and carries exactly two fields:

```java
public class Account {
    private String     userId;   // unique identifier
    private BigDecimal balance;  // outstanding balance, scale 2
}
```

It exists to represent the real-world concept of a customer's account within the payment domain. All business operations (deducting a payment, checking balance) act on this object.

**Why it's a plain POJO (no annotations):** The domain model must not import persistence or HTTP framework annotations. Redis-specific concerns (`@RedisHash`, `@Id`) live in `AccountEntity` in the `infra` module. This keeps `Account` portable and independently testable.

---

## Q2: Why is `balance` typed as `BigDecimal` instead of `double` or `float`?

**A:** `double` and `float` use binary floating-point representation, which cannot represent most decimal fractions exactly.

```java
double x = 0.1 + 0.2;  // → 0.30000000000000004, not 0.30
```

For financial arithmetic this is unacceptable. `BigDecimal` provides:
- **Exact decimal representation** — `0.1 + 0.2 = 0.3` exactly.
- **Controlled rounding** — you specify `RoundingMode.HALF_UP` explicitly when dividing or scaling.
- **Scale management** — `setScale(2, HALF_UP)` always produces a two-decimal result (e.g., `89.70` not `89.7`).

**Disadvantage:** `BigDecimal` is slower and more verbose than primitive arithmetic. For a payment service correctness outweighs performance.

---

## Q3: What is `PaymentResult` and why is it a Java `record`?

**A:** `PaymentResult` is the **output value object** from `ProcessPaymentUseCase`. It bundles every piece of data the HTTP response needs:

```java
public record PaymentResult(
    String     userId,
    BigDecimal previousBalance,
    BigDecimal paymentAmount,
    int        matchPercentage,
    BigDecimal matchAmount,
    BigDecimal newBalance,
    LocalDate  nextPaymentDueDate,
    LocalDate  paymentDate) { }
```

**Why a record?**
- Records are **immutable by default** — no setters, all fields `final` via the compact constructor. This is correct for a value object that should never change after creation.
- Boilerplate-free — no Lombok `@Data`, `@Builder`, or manual `equals`/`hashCode`/`toString` needed.
- Clearly signals intent: "this is data, not behaviour."

**Why does `PaymentResult` include `matchPercentage` if it's not in the HTTP response?**
Because the domain should not know or care what the HTTP layer exposes. The mapper (`PaymentResponseMapper`) picks only the fields needed for `OneTimePaymentResponse`. If a future API version wants to expose the match percentage, no domain change is required — only the mapper and the OpenAPI spec change.

---

## Q4: Why does `Account` use Lombok (`@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`) while `PaymentResult` uses a Java record?

**A:** The design intent differs:

| Class | Why this form |
|---|---|
| `Account` | Needs to be **mutable** (balance is updated by the use-case via `setBalance()`). Lombok `@Data` generates setters. `@Builder` makes test construction ergonomic. |
| `PaymentResult` | Should be **immutable** — it is a snapshot of the outcome. Records enforce immutability and are more concise for pure data containers. |

An improvement would be to make `Account` immutable too — instead of `setBalance()`, return a new `Account` with the updated balance:
```java
Account updated = Account.builder().userId(account.getUserId()).balance(newBalance).build();
accountSpi.save(updated);
```
This would align better with functional domain modelling and prevent accidental mutations.

---

## Q5: Why is `userId` a `String` rather than a typed ID or UUID?

**A:** Using a plain `String` is pragmatic for the current scope. Advantages:
- Simple, flexible — works with any ID format (UUIDs, opaque strings, human-readable IDs like `user-001`).
- No conversion overhead at boundaries.

Disadvantages and improvements:
- No type safety — you could accidentally pass a balance string where a userId is expected.
- A **Value Object** (e.g., `UserId` wrapping `String`) would prevent these mix-ups and add domain meaning:
  ```java
  public record UserId(String value) {
      public UserId { Objects.requireNonNull(value); if (value.isBlank()) throw ...; }
  }
  ```
- For production, using a UUID and validating the format at the API boundary would be preferable.

---

## Q6: What advantages does separating `AccountEntity` (infra) from `Account` (domain) provide?

**A:**

| Concern | `Account` (domain) | `AccountEntity` (infra) |
|---|---|---|
| Persistence annotations | None | `@RedisHash("account")`, `@Id` |
| Spring Data dependency | None | Yes (`CrudRepository`) |
| Testability | Instantiated in any unit test | Requires Spring context |
| Portability | Used by any adapter | Redis-specific |

If the project migrates to JPA/PostgreSQL, a new `AccountJpaEntity` is written and a new `AccountJpaAdapter` implements `AccountSpi`. The `Account` domain class and all business logic remain unchanged.

**Disadvantage:** Every save/fetch requires a mapper translation (`AccountEntity ↔ Account`). MapStruct generates this code at compile time, so the runtime cost is negligible, but there is mental overhead from the indirection.

---

## Q7: What improvements could be made to the domain model?

1. **Immutable `Account`** — replace mutable setters with a `withBalance(BigDecimal)` method or use a record, returning a new instance.
2. **Value Objects** — introduce `UserId`, `Money` (wrapping `BigDecimal` with currency), `MatchPercentage` for stronger type safety.
3. **Domain invariants on `Account`** — the constructor could enforce `balance >= 0` and `userId != null/blank`, raising `IllegalArgumentException` rather than letting invalid state propagate.
4. **Currency awareness** — `BigDecimal` has no notion of currency. A `Money(amount, currency)` value object would prevent cross-currency arithmetic bugs in a multi-currency future.
5. **Audit fields** — adding `createdAt` and `updatedAt` to `Account` would support future audit and history requirements without an architecture change.
