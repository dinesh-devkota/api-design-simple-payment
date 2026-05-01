# Q&A 23 — `BigDecimal`, Financial Arithmetic, and Why Floating-Point Fails

> The most commonly-failed topic in payments interviews. If you handle money with `double`, you will be asked to leave.

---

## Q1: Why is `double` catastrophically wrong for financial arithmetic?

**A:** `double` uses IEEE 754 binary floating-point representation. It can only represent numbers that are sums of negative powers of 2 (½, ¼, ⅛, ...). Most decimal fractions cannot be represented exactly:

```java
System.out.println(0.1 + 0.2);    // 0.30000000000000004
System.out.println(0.1 * 3);      // 0.30000000000000004
System.out.println(1.005 * 100);  // 100.49999999999999 (rounds DOWN, not up)
```

In a payment context:
```java
double balance = 100.00;
double payment = 10.00;
double match   = payment * 0.03;      // 0.30000000000000004 (not 0.30)
double newBalance = balance - payment - match; // 89.69999999999999 (not 89.70)
```

The user would see `$89.70` but the internal value is `$89.6999...`, and the *next* calculation compounds the error. Over millions of transactions, these errors are not random — they accumulate in one direction, creating systematic discrepancies.

**`float` is even worse** — it has half the precision of `double`.

---

## Q2: How does `BigDecimal` avoid these errors?

**A:** `BigDecimal` represents numbers internally as an **arbitrary-precision integer (unscaled value) and a scale (decimal exponent)**:

```
BigDecimal("89.70") = unscaled 8970, scale 2
BigDecimal("0.30")  = unscaled 30,   scale 2
```

Arithmetic operates on these integer representations:
```
89.70 + 0.30 = 8970×10⁻² + 30×10⁻² = 9000×10⁻² = 90.00
```

No binary approximation — all arithmetic is **exact in the decimal domain**. The only loss of precision occurs when you explicitly ask for rounding (division, `setScale()`).

---

## Q3: What is `scale` in `BigDecimal` and why does it matter?

**A:** Scale is the number of digits to the right of the decimal point.

```java
new BigDecimal("100")      // scale = 0, value = 100
new BigDecimal("100.00")   // scale = 2, value = 100.00
new BigDecimal("100.0")    // scale = 1, value = 100.0
```

All three represent the same mathematical value, but:
- `new BigDecimal("100").equals(new BigDecimal("100.00"))` → **false** (different scale)
- `new BigDecimal("100").compareTo(new BigDecimal("100.00"))` → **0** (same value)

This is why the codebase uses `compareTo` everywhere, **never** `equals`, for `BigDecimal` comparisons:

```java
// WRONG — returns false even if values are equal but scales differ:
if (paymentAmount.equals(BigDecimal.ZERO)) { ... }

// CORRECT — compares by value, ignores scale:
if (paymentAmount.compareTo(BigDecimal.ZERO) <= 0) { ... }
```

---

## Q4: What does `setScale(2, RoundingMode.HALF_UP)` do exactly?

**A:** It reduces (or expands) the `BigDecimal` to exactly 2 decimal places, using `HALF_UP` rounding:

```java
new BigDecimal("89.7").setScale(2, RoundingMode.HALF_UP)   // → 89.70
new BigDecimal("0.0999").setScale(2, RoundingMode.HALF_UP) // → 0.10  (0.0999 rounds up)
new BigDecimal("1.4997").setScale(2, RoundingMode.HALF_UP) // → 1.50  (1.4997 rounds up)
new BigDecimal("1.4940").setScale(2, RoundingMode.HALF_UP) // → 1.49  (1.4940 rounds down)
```

`HALF_UP` means: "if the digit to be dropped is exactly 5, round away from zero." This is the "schoolbook" rounding most users expect.

---

## Q5: What are the other `RoundingMode` values and when would you use them?

**A:**

| RoundingMode | Rule | Use case |
|---|---|---|
| `HALF_UP` | 0.5 rounds away from zero | Consumer-facing display (used in this project) |
| `HALF_EVEN` | 0.5 rounds to the nearest even digit (banker's rounding) | High-volume financial aggregation — minimises cumulative bias |
| `HALF_DOWN` | 0.5 rounds toward zero | Rare |
| `UP` | Always rounds away from zero | Tax calculations (always round in favour of government) |
| `DOWN` | Always truncates (toward zero) | Floor calculations, truncating decimal displays |
| `CEILING` | Rounds toward positive infinity | Interest accrual (always round up for creditor) |
| `FLOOR` | Rounds toward negative infinity | Discount calculations |
| `UNNECESSARY` | Throws if rounding is required | Assert that no rounding occurs — use in tests |

**`HALF_UP` vs `HALF_EVEN` in practice:**
Over 1000 transactions each rounding at exactly the 0.5 boundary:
- `HALF_UP`: all 1000 round up → cumulative bias of +$0.005 × 1000 = **+$5.00 error**
- `HALF_EVEN`: ~500 round up, ~500 round down → **≈$0.00 net error**

For a system processing millions of transactions per day, `HALF_EVEN` prevents systematic bias. This project uses `HALF_UP` because it's consumer-facing and "feels right" to users, which is acceptable at this scale.

---

## Q6: Why are the tier boundary constants declared as `static final BigDecimal` rather than inline?

**A:**
```java
private static final BigDecimal TEN   = BigDecimal.valueOf(10);
private static final BigDecimal FIFTY = BigDecimal.valueOf(50);
```

**Performance:** `BigDecimal` objects are heap-allocated. Declaring them `static final` creates them once when the class is loaded. Without this, every call to `getMatchPercentage()` would allocate new `BigDecimal` objects for `10` and `50`, creating GC pressure in a high-throughput service.

**Correctness:** `BigDecimal.valueOf(10)` vs `new BigDecimal("10")`:
- `BigDecimal.valueOf(10)` converts a `long` to `BigDecimal` — the result has scale 0: `10`.
- `new BigDecimal("10.0")` would have scale 1: `10.0`.
- For `compareTo`, both work correctly. But `BigDecimal.valueOf(10)` is cleaner and avoids any scale ambiguity.

**Readability:** Named constants document intent: `TEN` is not just a number — it's the boundary between the low and mid tiers.

---

## Q7: What is the difference between `BigDecimal.valueOf(10)` and `new BigDecimal("10")` and `new BigDecimal(10)`?

**A:**

| Constructor | Value | Scale | Notes |
|---|---|---|---|
| `new BigDecimal(10)` | 10 | 0 | Takes an `int` — safe for integers |
| `new BigDecimal(10.0)` | **10.0000000000000000000000000** | 55(!) | Takes a `double` — DO NOT USE for financial values |
| `new BigDecimal("10")` | 10 | 0 | Takes a `String` — exact, preferred |
| `new BigDecimal("10.00")` | 10.00 | 2 | Preserves scale |
| `BigDecimal.valueOf(10)` | 10 | 0 | Takes a `long` — exact, convenient for integer values |
| `BigDecimal.valueOf(10.0)` | 10.0 | 1 | Takes a `double` — uses `Double.toString(10.0)` = `"10.0"`, so technically exact here but dangerous in general |

**The rule:** For financial `BigDecimal` construction, always use `String` literals or `BigDecimal.valueOf(long)`. **Never** use `new BigDecimal(double)`.

---

## Q8: How is `BigDecimal` stored in Redis and what can go wrong?

**A:** Spring Data Redis stores `BigDecimal` fields as their `toString()` value: `"100.00"`. On read, Spring Data converts the string back to `BigDecimal` using `new BigDecimal("100.00")`.

**What can go wrong:**

1. **Manual Redis writes with wrong format** — the seed script writes `"100.00"`. If someone manually writes `HSET account:user-001 balance 100` (no decimal places), `new BigDecimal("100")` has scale 0. `setScale(2, HALF_UP)` later will expand it to `100.00`. The value is mathematically correct, but `equals("100.00")` would return `false` due to scale difference — only `compareTo` would work.

2. **Scientific notation** — `BigDecimal` can serialize to `"1E+2"` for `new BigDecimal("100").stripTrailingZeros()`. If the `balance` is stored as `"1E+2"` in Redis, `new BigDecimal("1E+2")` reads it back correctly, but the raw Redis value is unreadable in RedisInsight. Always use `toPlainString()` or `setScale(2, HALF_UP).toString()` for consistent storage.

3. **Locale issues** — `BigDecimal.toString()` uses a period as the decimal separator, which is locale-independent. Safe. But if `String.format("%.2f", balance)` is used instead, it would use the JVM's locale (which could produce `"100,00"` in a European locale). Never format `BigDecimal` with `String.format` for storage.

---

## Q9: What is the exact calculation for `$9.99` at the 1% tier and why does the match round to `$0.10`?

**A:**
```java
BigDecimal payment = new BigDecimal("9.99");
int pct = 1;
BigDecimal matchAmount = payment
    .multiply(BigDecimal.valueOf(pct))   // 9.99 × 1 = 9.99
    .divide(HUNDRED, 2, RoundingMode.HALF_UP); // 9.99 / 100 = 0.0999 → rounds to 0.10
```

Step by step:
1. `9.99 × 1 = 9.99`
2. `9.99 / 100 = 0.0999` (exact intermediate result)
3. `setScale(2, HALF_UP)`: the third decimal is `9` ≥ 5, so round up: `0.0999 → 0.10`

The test:
```java
"9.99, 0.10"  // 9.99 * 1% = 0.0999 → HALF_UP → 0.10
```

---

## Q10: What is the exact balance calculation and how is it rounded?

**A:**
```java
BigDecimal newBalance = previousBalance
    .subtract(totalDeduction)     // exact subtraction
    .setScale(2, RoundingMode.HALF_UP);  // round result to 2dp
```

For `user-001` ($100.00 balance, $10.00 payment):
```
totalDeduction = 10.00 + 0.30 = 10.30
newBalance = 100.00 - 10.30 = 89.70      (exact, no rounding needed)
setScale(2, HALF_UP) → 89.70             (already scale 2, no change)
```

The `setScale(2, HALF_UP)` on the final result is a **defensive measure** — subtraction of two scale-2 `BigDecimal` values always produces a scale-2 result, so no rounding actually occurs in this case. But if any upstream calculation produced more decimal places (e.g., from a future percentage computation), the `setScale` would catch it.
