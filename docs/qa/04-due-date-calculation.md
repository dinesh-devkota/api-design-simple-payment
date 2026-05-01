# Q&A 04 — Due Date Calculation Service

> Covers the +15-day rule, weekend shifting, Java's switch expression, and testing strategy.

---

## Q1: What does `DueDateCalculationService` do?

**A:** It takes a payment date and returns the next payment due date according to two rules:
1. Add 15 calendar days.
2. If the resulting date falls on a **Saturday**, add 2 more days (move to Monday).
3. If the resulting date falls on a **Sunday**, add 1 more day (move to Monday).
4. Any other day of the week is returned as-is.

```java
public LocalDate calculateDueDate(LocalDate paymentDate) {
    LocalDate rawDueDate = paymentDate.plusDays(15);
    return switch (rawDueDate.getDayOfWeek()) {
        case SATURDAY -> rawDueDate.plusDays(2);
        case SUNDAY   -> rawDueDate.plusDays(1);
        default       -> rawDueDate;
    };
}
```

---

## Q2: Why is the due date 15 calendar days rather than 2 business weeks?

**A:** This is a **product decision** specified in the requirements: "the next payment due date is always 15 days into the future." Calendar days are simpler to calculate and communicate to users. Business days would require a holiday calendar (which differs by country/region) and add significant complexity. The weekend-shift rule softens the impact: no one ever receives a due date on a weekend.

---

## Q3: Why does the weekend shift go to the *next* Monday rather than the preceding Friday?

**A:** Again, a product decision: moving the date *forward* is more consumer-friendly — it gives the user more time, rather than surprising them with an earlier deadline. From the requirements: "any payment due dates that fall on a Saturday or Sunday should be moved out to the next Monday."

---

## Q4: Why is a Java `switch` expression used instead of `if-else`?

**A:** The `switch` expression (Java 14+, stable in Java 16+) used here is:
1. **Exhaustive** — the compiler ensures all enum values are handled (either explicitly or via `default`). An `if-else` chain is not exhaustive.
2. **Expression** — the whole `switch` is an expression returning a value, so a single `return` suffices (no intermediate variable needed).
3. **No fall-through** — arrow-case syntax (`->`) eliminates the classic `break` omission bug.
4. **Readable** — the intent "shift Saturday by 2, Sunday by 1, otherwise keep" is immediately clear.

**Disadvantage:** Requires Java 14+ (stable 16+). This project targets Java 21 so this is not a concern.

---

## Q5: How does the test suite verify all seven days of the week?

**A:** `DueDateCalculationServiceTest` uses a clever mathematical property:

> 15 mod 7 = 1

This means adding 15 days advances the day-of-week by exactly 1. By using a consecutive 7-day block (`2026-04-10` to `2026-04-16`), each payment date produces a distinct day-of-week outcome for `+15`:

| Payment date | +15 raw | Day of week | Expected output |
|---|---|---|---|
| 2026-04-10 (Fri) | 2026-04-25 | Saturday | 2026-04-27 (Mon) |
| 2026-04-11 (Sat) | 2026-04-26 | Sunday | 2026-04-27 (Mon) |
| 2026-04-12 (Sun) | 2026-04-27 | Monday | 2026-04-27 (no shift) |
| 2026-04-13 (Mon) | 2026-04-28 | Tuesday | 2026-04-28 (no shift) |
| 2026-04-14 (Tue) | 2026-04-29 | Wednesday | 2026-04-29 (no shift) |
| 2026-04-15 (Wed) | 2026-04-30 | Thursday | 2026-04-30 (no shift) |
| 2026-04-16 (Thu) | 2026-05-01 | Friday | 2026-05-01 (no shift) |

All 7 `DayOfWeek` values are covered with 7 test cases — no redundancy.

---

## Q6: Why does `ProcessPaymentService` inject a `Clock` rather than calling `LocalDate.now()` directly?

**A:** `LocalDate.now()` binds to the system clock at call time, making date-dependent tests non-deterministic (the result changes depending on when the test runs). By accepting a `Clock` dependency:

```java
LocalDate today = LocalDate.now(clock);
```

- **Unit tests** create a fixed `Clock.fixed(...)` so the date is always known.
- **Integration tests** also use a fixed clock (or just verify the field is present).
- **Production** injects `Clock.systemDefaultZone()` via `ClockConfig`.
- The `ClockConfig.fixedClock` bean (activated by `app.fixed-date=YYYY-MM-DD`) allows a developer to manually trigger a weekend-shift scenario via Swagger UI without waiting for the right calendar day.

This pattern is a core principle of **dependency injection for time** and should be used in any service where date logic must be tested deterministically.

---

## Q7: What are the advantages and disadvantages of this implementation?

**Advantages:**
| Advantage | Detail |
|---|---|
| Simplicity | One method, ~5 lines of logic |
| Testability | Fully testable with plain `LocalDate` inputs — no mocking needed |
| Immutability | `LocalDate` is immutable; `plusDays` returns a new object |
| Exhaustive switching | Compiler validates all enum arms |

**Disadvantages:**
| Disadvantage | Detail |
|---|---|
| No holiday awareness | Bank holidays are not shifted; only weekends are handled |
| Fixed offset | "15 days" is hard-coded; changing it requires a code change |
| No timezone context | `paymentDate` is a `LocalDate` (no timezone). If users are in different timezones, "today" at the controller level may differ from their local "today" |

---

## Q8: What improvements could be made?

1. **Configurable offset** — inject the `15` as a property:
   ```yaml
   app.payment.due-date-offset-days: 15
   ```
2. **Holiday calendar** — integrate a public holiday API (or a `Set<LocalDate>` loaded from config) and skip those dates too:
   ```java
   while (isWeekend(date) || holidays.contains(date)) date = date.plusDays(1);
   ```
3. **Timezone-aware dates** — accept `ZonedDateTime` or `Instant` (with a `ZoneId`) instead of `LocalDate` so the due date is anchored to the user's timezone.
4. **Multiple skip rules** — if the business later adds "no due dates in December" or "no due dates on company-specific holidays," a `DueDateAdjuster` strategy list would be more extensible than modifying this single service.
5. **Return a `DueDate` value object** — instead of `LocalDate`, a `DueDate` VO could carry metadata like whether the date was shifted and why, useful for audit logging and UI display.
