# Q&A 12 — Over-Engineering vs. Intentional Design: Is Hexagonal Architecture Justified for One Endpoint?

> One of the most debated questions in software design: when does "good architecture" become "premature abstraction"?

---

## The Question

> *If someone builds four Maven modules, six interfaces, two mapper layers, and generated DTOs for a single `POST /one-time-payment` endpoint — is that bad practice, or does it show the developer is designing for scalability and maintainability?*

**Short answer: both, depending on context. But this codebase provides explicit signals that the structure is intentional — not accidental complexity.**

---

## The Case For "Over-Engineering"

### YAGNI — You Aren't Gonna Need It

A flat Spring Boot application with one controller, one service class, and one direct Redis call would deliver identical behaviour for the current scope:

```
PaymentController
  └── PaymentService       (match calc + due date inline)
       └── RedisTemplate   (direct, no adapter)
```

That's three classes. This project has **twenty-plus** to do the same thing today.

Every layer of indirection carries a **maintenance tax**:

| Indirection | Cost to a new developer |
|---|---|
| `ProcessPaymentUseCase` interface | Must understand why the interface exists before reading the implementation |
| `AccountSpi` + `AccountAdapter` | Must trace through two classes to understand "fetch account from Redis" |
| `AccountEntity` ↔ `Account` mapping | Must understand why two nearly identical classes exist |
| Generated DTOs from OpenAPI | Must know to run `mvn generate-sources` before the IDE works |

> **Rule of thumb:** if the architecture's overhead costs more than the flexibility it buys *right now*, it is premature.

---

## The Case For "Designed to Scale"

### This Codebase Has Explicit Signals It Is Built to Grow

**1. The storage layer is declared temporary.**

`openapi.yaml` documents it directly:

```yaml
description: |
  Iteration 1 (current)  — One-time payment via Redis-backed account store.
  Iteration 2 (planned)  — Oracle-backed persistence with full payment history and audit trail.
```

The `AccountSpi` / `AccountAdapter` abstraction exists *precisely* for this swap. When Oracle replaces Redis, the domain and REST layers are untouched — only `infra` changes. Without the adapter pattern, that migration touches every class that holds Redis logic.

---

**2. Payments domains always grow.**

One-time payments today. Tomorrow:

- Recurring payments (scheduled deductions)
- Partial payments and payment plans
- Refunds and reversals
- Payment history and audit trails
- Multi-currency support
- Fraud detection hooks

Each of these is a new use-case that slots cleanly into the existing structure. In a flat monolith, each addition risks entangling unrelated logic.

---

**3. Correctness matters more in fintech than in most domains.**

The domain isolation enables testing every tier-boundary and every weekend-shift scenario with **zero infrastructure**:

```java
// No Spring context. No Redis. No Docker. Runs in milliseconds.
useCase = new ProcessPaymentService(
    mockAccountSpi,
    new MatchCalculationServiceImpl(),
    new DueDateCalculationServiceImpl(),
    FIXED_CLOCK);
```

In a flat service, the same test requires spinning up Redis or mocking a concrete class — both are slower and more brittle. For a system handling real money, this isolation is not a luxury.

---

**4. The exercise context makes the intent explicit.**

The README states the evaluators are looking for:

> *"System design — logical method structure, domain modeling, and use of design patterns."*

A developer who produces this structure is not padding their solution. They are demonstrating that they can apply enterprise patterns *intentionally*, with documented rationale — which is exactly what the role requires.

---

## The Real Answer: It Is a Deliberate Trade-Off, Not a Mistake

The right question is not *"is this too much for one endpoint?"* but:

> *"Would I regret not having this structure when endpoints two, three, and four arrive?"*

| Scenario | Verdict |
|---|---|
| Service stays at one endpoint forever | Modest over-engineering — indirection cost > flexibility gained today |
| Service grows as the OpenAPI doc signals | Architecture was exactly right — every new feature slots in without touching existing layers |

A developer who builds this for one endpoint is making the following explicit statement:

> *"I know this looks like a lot for what we have today. I made this choice because the domain is expected to grow, storage needs to be swappable, and business rules must be testable in isolation. I could strip it to a flat service — and here is what that trade-off costs us later."*

**That is not bad practice. That is engineering judgment.**

---

## When It Would Cross Into Bad Practice

The same structure becomes a genuine problem if any of these are true:

| Anti-pattern | Why it matters |
|---|---|
| No documented rationale | The pattern looks cargo-culted — copied without understanding |
| Inconsistently applied | Some layers bypass the ports; others don't. Worse than no pattern at all |
| Developer cannot explain the trade-offs | Proves the structure was added mechanically, not thoughtfully |
| Prototype with a 2-week lifespan | YAGNI wins — the code will be thrown away before the architecture pays off |

This codebase avoids all four:
- Architecture is documented in `ARCHITECTURE.md` and inline Javadoc on every class.
- The pattern is applied consistently — no shortcuts, no bypassed layers.
- Every class carries a comment explaining its role and why it is separated.
- The service is explicitly positioned as the first iteration of a growing payments platform.

---

## The One-Line Summary

> **Cargo-culting a pattern** is copying structure without understanding it.  
> **Consciously applying a pattern** is choosing structure because you can articulate what it costs today and what it buys tomorrow.

This codebase is the latter.
