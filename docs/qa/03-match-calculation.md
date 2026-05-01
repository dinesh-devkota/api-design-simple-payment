# Q&A 03 — Match Calculation Service

> Covers the tiered match system, `BigDecimal` comparison, rounding, and testing approach.

---

## Q1: What does `MatchCalculationService` do and why is it a separate service?

**A:** It encapsulates one business rule: **given a payment amount, what percentage match does Paytient apply, and how many dollars is that?**

```
0 < amount < $10.00   → 1%
$10.00 ≤ amount < $50 → 3%
amount ≥ $50          → 5%
```

It is extracted into its own service (rather than inlined in `ProcessPaymentService`) because:
- **Single Responsibility** — tier logic can change independently of payment orchestration.
- **Testability** — `MatchCalculationServiceTest` covers every tier boundary with parameterized tests in isolation.
- **Reusability** — if future features need to calculate a match without processing a full payment, the service can be injected directly.

---

## Q2: Walk through `getMatchPercentage` step by step.

**A:**
```java
public int getMatchPercentage(BigDecimal paymentAmount) {
    if (paymentAmount.compareTo(TEN) < 0) {   // amount < 10
        return 1;
    } else if (paymentAmount.compareTo(FIFTY) < 0) { // 10 ≤ amount < 50
        return 3;
    } else {                                   // amount ≥ 50
        return 5;
    }
}
```

- `TEN`, `FIFTY`, `HUNDRED` are pre-declared `static final BigDecimal` constants. Creating them once avoids repeated object allocation.
- `compareTo` is used, **not** `equals()`. This is critical: `new BigDecimal("10.0").equals(new BigDecimal("10.00"))` returns **false** because `equals` includes scale. `compareTo` returns 0 for equal numeric values regardless of scale, making the boundary checks correct.

---

## Q3: Why does `calculateMatchAmount` use `RoundingMode.HALF_UP`?

**A:**
```java
return paymentAmount
    .multiply(BigDecimal.valueOf(pct))
    .divide(HUNDRED, 2, RoundingMode.HALF_UP);
```

`HALF_UP` mirrors everyday consumer-facing rounding:
- 0.5 rounds up to 1 (not toward even as `HALF_EVEN` would do).
- Example: `$9.99 × 1% = $0.0999` → rounded to `$0.10` (HALF_UP).

**Why not `HALF_EVEN` (banker's rounding)?**
`HALF_EVEN` reduces cumulative rounding bias over large volumes of transactions (used in banking/accounting). For a consumer-facing UI display `HALF_UP` is more intuitive. If this system processed millions of transactions daily, `HALF_EVEN` would be preferable to avoid systematic bias.

**Why scale 2?**
US dollars have two decimal places of precision. Storing more than 2 decimal places would be misleading in a currency context.

---

## Q4: What happens for a $0.01 payment (the minimum)?

**A:** `$0.01 × 1% = $0.0001`. When rounded to 2 decimal places with `HALF_UP`, this is `$0.00` — the match is effectively zero. The test explicitly covers this edge case:

```
"0.01, 0.00"  // sub-cent match rounds to $0.00
```

This is a **business decision worth documenting**: a user paying one cent gets no match benefit. An improvement would be to define a minimum match floor (e.g., always at least $0.01 if the payment is non-zero), but that would require a product decision.

---

## Q5: How are tier boundaries tested?

**A:** `MatchCalculationServiceTest` uses JUnit 5 `@ParameterizedTest` with `@CsvSource`:

```java
@CsvSource({
    "9.99,  1",   // top of low tier
    "10.00, 3",   // bottom of mid tier (boundary!)
    "49.99, 3",   // top of mid tier
    "50.00, 5",   // bottom of high tier (boundary!)
})
```

The test specifically exercises the **exact boundary values** (`10.00` and `50.00`) because off-by-one errors in `<` vs `<=` comparisons are a classic bug. If the condition were incorrectly written as `compareTo(TEN) <= 0` the `10.00` test case would fail (returning 1 instead of 3).

---

## Q6: What are the advantages and disadvantages of this implementation?

**Advantages:**
- Clean, readable if-else chain that maps directly to the specification table.
- `BigDecimal` constants declared once — no repeated allocations.
- `compareTo` used correctly, avoiding scale-sensitive `equals` bugs.
- Fully decoupled from HTTP, Redis, and the payment orchestrator.

**Disadvantages / trade-offs:**
| Issue | Detail |
|---|---|
| Hard-coded tiers | Adding a new tier requires code change + redeployment |
| No currency guard | A negative `paymentAmount` passed here would return `1` (low tier) with no validation — validation is done upstream in `ProcessPaymentService` |
| Integer percentage | Returning `int` limits future precision (e.g., a 1.5% match would require a `BigDecimal` return type) |

---

## Q7: What improvements could be made to the match calculation?

1. **Configurable tiers** — define tiers in application YAML or a database table so product can change them without a code deployment:
   ```yaml
   match.tiers:
     - maxExclusive: 10.00
       percentage: 1
     - maxExclusive: 50.00
       percentage: 3
     - percentage: 5  # catch-all
   ```
   Load into a `List<MatchTier>` and iterate to find the applicable tier.

2. **Return `BigDecimal` for percentage** — allows fractional percentages (e.g., `2.5%`) without a breaking API change.

3. **Match cap** — impose a maximum match amount (e.g., Paytient won't match more than $50 per payment) to limit liability.

4. **Separate `getMatchPercentage` from `calculateMatchAmount`** — currently `calculateMatchAmount` calls `getMatchPercentage` internally. Callers who need both the percentage and the amount must call `getMatchPercentage` separately to get the raw percentage. This is already the case in `ProcessPaymentService`, so the design is fine, but documenting the intended usage pattern would help.

5. **Replace if-else with a strategy or sorted tier list** — as tiers multiply, a sorted `NavigableMap<BigDecimal, Integer>` lookup would be more maintainable than an ever-growing if-else chain.
