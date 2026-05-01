# Design Patterns Documentation - Complete Index

## 📚 Overview

This directory contains three comprehensive guides to the design patterns used in the Customer Care API:

### Documents

| Document | Purpose | Best For |
|----------|---------|----------|
| **DESIGN-PATTERNS.md** | Comprehensive deep-dive | Learning & reference |
| **DESIGN-PATTERNS-CHEATSHEET.md** | Quick lookup & visual summaries | Quick decisions |
| **DESIGN-PATTERNS-CODE-EXAMPLES.md** | Real code + flow diagrams | Understanding implementation |

---

## 🎯 Quick Start Guide

### I want to...

**Understand all patterns used:**
→ Start with **DESIGN-PATTERNS-CHEATSHEET.md** (Section 1-3)

**Learn why a pattern is used:**
→ Go to **DESIGN-PATTERNS.md** (find the pattern, read Pros/Cons)

**See the pattern in actual code:**
→ Visit **DESIGN-PATTERNS-CODE-EXAMPLES.md** (search for the pattern)

**Make a decision about adding patterns:**
→ Check **DESIGN-PATTERNS-CHEATSHEET.md** (Section 10)

**Understand the big picture:**
→ Read **DESIGN-PATTERNS-CODE-EXAMPLES.md** (Pattern Interaction Examples, Flow Diagram)

---

## 📋 Pattern Summary

### 12 Patterns Identified

```
┌─────────────────────────────────────────────────────────────┐
│ 1.  Hexagonal Architecture (Ports & Adapters)   ⭐⭐⭐⭐⭐ │
│ 2.  Repository Pattern                          ⭐⭐⭐⭐  │
│ 3.  Strategy Pattern                            ⭐⭐⭐⭐  │
│ 4.  Mapper Pattern (MapStruct)                  ⭐⭐⭐⭐  │
│ 5.  Adapter Pattern                             ⭐⭐⭐⭐  │
│ 6.  Factory Pattern                             ⭐⭐⭐   │
│ 7.  Template Method Pattern                     ⭐⭐⭐   │
│ 8.  Centralized Exception Handler (Advice)      ⭐⭐⭐⭐  │
│ 9.  Builder Pattern                             ⭐⭐⭐   │
│ 10. Dependency Injection                        ⭐⭐⭐⭐⭐ │
│ 11. Optional Pattern                            ⭐⭐⭐⭐  │
│ 12. Value Object Pattern                        ⭐⭐⭐   │
└─────────────────────────────────────────────────────────────┘
```

### Pros & Cons Matrix

| Pattern | Main Pro | Main Con | Trade-off |
|---------|----------|----------|-----------|
| **Hexagonal** | Framework independence | More modules | Worth it |
| **Repository** | Persistence agnostic | Extra layer | Manageable |
| **Strategy** | Reusable algorithms | Indirection | Necessary |
| **Mapper** | Type-safe conversion | Compilation step | Fast runtime |
| **Adapter** | System integration | More classes | Clean separation |
| **Factory** | Runtime selection | Hidden creation | Auto-wiring clear |
| **Template Method** | Algorithm skeleton | Rigid structure | Stable design |
| **Exception Advice** | Centralized handling | Hidden behavior | Good docs |
| **Builder** | Fluent API | More verbose | Self-documenting |
| **Dependency Injection** | Easy testing | Learning curve | Standard practice |
| **Optional** | Null-safety | Extra object | Minimal overhead |
| **Value Object** | Immutability | Create-and-discard | Thread-safe |

---

## 📍 Where Each Pattern Is Used

### In Application Layer (app/)

```
app/
├── rest/
│   ├── PaymentController
│   │   ├── Uses: Dependency Injection
│   │   ├── Uses: Strategy (Supplier<T>)
│   │   ├── Uses: Template Method (via IdempotencyGuard)
│   │   └── Calls: Mapper
│   │
│   └── HelloController
│       └── Simple example (minimal patterns)
│
├── handler/
│   └── GlobalExceptionHandler
│       ├── Pattern: Advice Pattern (@RestControllerAdvice)
│       ├── Pattern: Value Object (ErrorResponse)
│       └── Pattern: Factory (buildResponse helper)
│
├── mapper/
│   └── PaymentResponseMapper
│       └── Pattern: Mapper (MapStruct)
│       └── Generated at compile time
│
└── idempotency/
    └── IdempotencyGuard
        ├── Pattern: Strategy (Supplier<T>)
        ├── Pattern: Template Method (algorithm skeleton)
        ├── Pattern: Adapter (uses IdempotencyStoreSpi)
        └── Pattern: Dependency Injection
```

### In Domain Layer (domain/)

```
domain/
├── model/
│   └── Account
│       ├── Pattern: Builder (@Builder)
│       └── Pattern: POJO (no annotations)
│
├── payment/
│   ├── ProcessPaymentUseCase (interface)
│   │   └── Pattern: Primary Port (Hexagonal)
│   │
│   ├── ProcessPaymentService
│   │   ├── Pattern: Dependency Injection
│   │   ├── Pattern: Repository (via AccountSpi)
│   │   ├── Pattern: Strategy (Match%, DueDate services)
│   │   ├── Pattern: Factory (Clock injection)
│   │   └── Uses: Business Rules
│   │
│   └── PaymentResult (record)
│       └── Pattern: Value Object (immutable)
│
├── service/
│   ├── MatchCalculationService (interface)
│   │   └── Pattern: Strategy (tier algorithms)
│   │
│   ├── DueDateCalculationService (interface)
│   │   └── Pattern: Strategy (day-of-week logic)
│   │
│   └── impl/ (implementations)
│       └── Uses: Dependency Injection (@Service)
│
├── spi/
│   ├── AccountSpi (interface)
│   │   └── Pattern: Secondary Port (Hexagonal)
│   │
│   └── IdempotencyStoreSpi (interface)
│       └── Pattern: Secondary Port (Hexagonal)
│
└── exception/
    ├── AccountNotFoundException
    ├── InvalidPaymentAmountException
    └── InsufficientBalanceException
        └── Pattern: Domain-Driven Exceptions
```

### In Infrastructure Layer (infra/)

```
infra/
├── config/
│   └── RedisConfig
│       ├── Pattern: Factory (@Bean methods)
│       ├── Pattern: Conditional beans
│       └── Pattern: Spring configuration
│
├── redis/
│   ├── adapter/
│   │   ├── AccountAdapter
│   │   │   ├── Pattern: Adapter (implements AccountSpi)
│   │   │   ├── Pattern: Repository (wraps Spring Data)
│   │   │   ├── Pattern: Mapper (uses AccountEntityMapper)
│   │   │   └── Pattern: Dependency Injection
│   │   │
│   │   └── RedisIdempotencyStore
│   │       ├── Pattern: Adapter (implements IdempotencyStoreSpi)
│   │       ├── Pattern: Template Method (try-catch pattern)
│   │       └── Pattern: Dependency Injection
│   │
│   ├── entity/
│   │   └── AccountEntity
│   │       ├── Pattern: Mapper input (@RedisHash entity)
│   │       └── Pattern: Builder (via Lombok)
│   │
│   ├── mapper/
│   │   └── AccountEntityMapper
│   │       └── Pattern: Mapper (MapStruct, bidirectional)
│   │
│   └── repository/
│       └── AccountRedisRepository
│           ├── Pattern: Repository (Spring Data)
│           └── Pattern: CRUD operations
```

### In Bootstrap Layer (bootstrap/)

```
bootstrap/
├── CustomerCareApplication
│   └── Pattern: Spring Boot entry point
│       └── Pattern: Hexagonal wiring (@SpringBootApplication)
│
└── config/
    └── ClockConfig
        ├── Pattern: Factory (two Clock implementations)
        ├── Pattern: Conditional beans (@ConditionalOnProperty)
        └── Pattern: Dependency Injection
```

---

## 🔄 Cross-Pattern Interactions

### Most Common Combinations

```
1. HEXAGONAL + REPOSITORY + ADAPTER
   How: Domain defines SPI → Infra provides adapter →
        adapter uses Spring Data repository
   Why: Enables persistence swapping
   Example: AccountSpi → AccountAdapter → AccountRedisRepository

2. DEPENDENCY INJECTION + FACTORY
   How: Spring creates beans, injects based on conditions
   Why: Flexible runtime behavior without code changes
   Example: Clock.fixed for tests, Clock.system for prod

3. MAPPER + STRATEGY + TEMPLATE METHOD
   How: Reusable mappers, pluggable suppliers, fixed algorithm
   Why: Compose complex operations from simple pieces
   Example: IdempotencyGuard uses Supplier + Mapper

4. ADAPTER + MAPPER
   How: Adapter uses mapper to convert between layers
   Why: Clean separation of formats
   Example: RedisIdempotencyStore uses Jackson mapper

5. EXCEPTION ADVICE + VALUE OBJECT
   How: Handler creates consistent ErrorResponse
   Why: All errors follow same structure
   Example: GlobalExceptionHandler builds ErrorResponse record
```

---

## 📊 Pattern Maturity Levels

### Level 1: Beginner-Friendly Patterns

```
Patterns commonly used by junior developers:
✓ Builder
✓ Optional
✓ Dependency Injection (Spring)
✓ Value Object

Learning curve: Low
Implementation: Straightforward
Often covered in tutorials: Yes
```

### Level 2: Intermediate Patterns

```
Patterns used by confident mid-level developers:
✓ Repository
✓ Mapper
✓ Strategy
✓ Factory
✓ Exception Handler (Advice)

Learning curve: Medium
Implementation: Moderate
Often covered in tutorials: Sometimes
Professional experience needed: Yes
```

### Level 3: Advanced Patterns

```
Patterns used by experienced engineers:
✓ Hexagonal Architecture
✓ Adapter Pattern (cross-system)
✓ Template Method (with strategy)
✓ Ports & Adapters (strategic design)

Learning curve: High
Implementation: Complex (requires planning)
Often covered in tutorials: Rare
Professional experience needed: Strongly yes
Advanced concepts: System thinking, boundaries
```

---

## ✅ Pattern Verification Checklist

### Before Using a Pattern, Ask:

- [ ] **Is there a real problem this solves?** (Not "just in case")
- [ ] **Is it used more than once?** (DRY principle)
- [ ] **Will team understand it?** (Not job security via obscurity)
- [ ] **Are benefits worth the complexity?**
- [ ] **Is it consistent with codebase?** (Don't mix styles)

### After Using a Pattern, Check:

- [ ] **Is it documented?** (Code comments explaining why)
- [ ] **Can new team members find examples?** (Shows how it's used)
- [ ] **Is it tested?** (Unit tests for behavior)
- [ ] **Did it solve the problem?** (Measure benefits)
- [ ] **Can it be removed safely?** (Not over-committed)

---

## 🎓 Learning Path

### Week 1: Fundamentals
1. Read DESIGN-PATTERNS-CHEATSHEET.md (Sections 1-5)
2. Understand Builder, Optional, Value Object
3. Run the code; trace execution

### Week 2: Intermediate
1. Read DESIGN-PATTERNS.md (Mapper, Strategy, Repository)
2. Understand how they reduce boilerplate
3. Write test for AccountAdapter

### Week 3: Advanced
1. Read DESIGN-PATTERNS.md (Hexagonal, Adapter, Factory)
2. Understand isolation and flexibility
3. Design a hypothetical adapter (e.g., Oracle instead of Redis)

### Week 4: Synthesis
1. Read DESIGN-PATTERNS-CODE-EXAMPLES.md (Pattern Flow)
2. Trace a payment request through all patterns
3. Write design document for a new feature using these patterns

---

## 📖 Cross-References

### By Concern

**I want to test this code without a database:**
→ Repository Pattern (DESIGN-PATTERNS.md §2)
→ Adapter Pattern (DESIGN-PATTERNS.md §5)
→ Code Example (DESIGN-PATTERNS-CODE-EXAMPLES.md §3a)

**I need to handle errors consistently:**
→ Exception Advice Pattern (DESIGN-PATTERNS.md §8)
→ Global Handler (DESIGN-PATTERNS-CODE-EXAMPLES.md §6)

**I'm worried about null pointers:**
→ Optional Pattern (DESIGN-PATTERNS.md §11)
→ Usage in code (DESIGN-PATTERNS-CODE-EXAMPLES.md §3)

**I need to swap implementations at runtime:**
→ Factory Pattern (DESIGN-PATTERNS.md §6)
→ Example: ClockConfig (DESIGN-PATTERNS-CODE-EXAMPLES.md)

**I'm creating too many converter classes:**
→ Mapper Pattern (DESIGN-PATTERNS.md §4)
→ MapStruct details (DESIGN-PATTERNS-CODE-EXAMPLES.md §3a.1)

---

## 🚀 Applying Patterns to Your Code

### Step-by-Step: Adding a New Endpoint

```
1. Define request/response DTOs (in app module)
   └─ Use Builder pattern (fluent construction)

2. Create domain use-case (in domain module)
   └─ Depends only on SPIs (secondary ports)
   └─ Inject dependencies (Dependency Injection)

3. Implement domain logic (in domain module)
   └─ Use strategy services (pattern delegation)
   └─ Return value object (immutable result)

4. Create response mapper (in app module)
   └─ Use MapStruct (type-safe, generated)

5. Create controller (in app module)
   └─ Inject use-case, mapper, guard
   └─ No business logic here (thin adapter)

6. Handle errors (GlobalExceptionHandler)
   └─ Domain exceptions automatically mapped
   └─ Consistent error responses (Advice pattern)

7. If using external system:
   └─ Define SPI in domain
   └─ Create adapter in infra
   └─ Implement via Spring Data or HTTP client

Result: 
✓ Testable (mock the SPIs)
✓ Maintainable (clear responsibilities)
✓ Swappable (implementations in infra layer)
```

---

## 🎯 Decision Tree: Which Pattern?

```
Do I need to...

├─ Convert between types?
│  └─ YES → USE: Mapper Pattern
│  └─ NO  → Continue
│
├─ Abstract a changing implementation?
│  └─ YES → USE: Adapter Pattern + Repository
│  └─ NO  → Continue
│
├─ Make algorithm structure reusable?
│  └─ YES → USE: Template Method + Strategy
│  └─ NO  → Continue
│
├─ Create complex objects?
│  └─ YES → USE: Builder
│  └─ NO  → Continue
│
├─ Handle errors consistently?
│  └─ YES → USE: Exception Advice (Advice Pattern)
│  └─ NO  → Continue
│
├─ Choose implementation at runtime?
│  └─ YES → USE: Factory Pattern
│  └─ NO  → Continue
│
├─ Avoid null pointer exceptions?
│  └─ YES → USE: Optional Pattern
│  └─ NO  → Continue
│
├─ Isolate business from infrastructure?
│  └─ YES → USE: Hexagonal Architecture (all of above)
│  └─ NO  → Use a simple monolith (not this project)
│
└─ Result: Design follows from needs, not vice versa
```

---

## Final Notes

### This Codebase Shows:

✅ **Pragmatic patterns**: Used because they solve real problems  
✅ **Professional standards**: Production-ready architecture  
✅ **Thoughtful trade-offs**: Each decision documented  
✅ **Not over-engineered**: Complex exactly enough, no more  
✅ **Team appropriate**: Learnable, not job-security via obscurity  

### Key Philosophy:

> **Patterns are tools, not rules.**
>
> Use when problems arise, not in anticipation of problems.
> Measure benefits vs complexity.
> Keep the team productive, not confused.

---

## Quick Links to Sections

### DESIGN-PATTERNS.md
- [Hexagonal Architecture](#1-hexagonal-architecture-ports--adapters)
- [Repository Pattern](#2-repository-pattern)
- [Strategy Pattern](#3-strategy-pattern)
- [Mapper Pattern](#4-mapper-pattern-mapstruct)
- [Adapter Pattern](#5-adapter-pattern)
- [Factory Pattern](#6-factory-pattern)
- [Template Method](#7-template-method-pattern)
- [Exception Advice](#8-centralized-exception-handler-advice-pattern)
- [Builder Pattern](#9-builder-pattern)
- [Dependency Injection](#10-dependency-injection)
- [Optional Pattern](#11-optional-pattern)
- [Value Object Pattern](#12-value-object-pattern)

### DESIGN-PATTERNS-CHEATSHEET.md
- [Pattern Distribution](#2️⃣-pattern-distribution)
- [Quick Scorecard](#5️⃣-pros-cons-quick-scorecard)
- [Decision Tree](#-cheat-sheet-when-to-add-patterns)

### DESIGN-PATTERNS-CODE-EXAMPLES.md
- [Complete Flow](#complete-flow-how-patterns-work-together)
- [Error Path Examples](#example-2-error-path)
- [Pattern Interactions](#pattern-relationships-diagram)

---

## Questions?

**"When should I use this pattern?"**
→ Check Pros/Cons in DESIGN-PATTERNS.md

**"Where is this pattern used in code?"**
→ Look up in DESIGN-PATTERNS-CODE-EXAMPLES.md

**"Is this pattern overkill?"**
→ See Trade-offs section in DESIGN-PATTERNS.md

**"How do I test around this pattern?"**
→ Check the pattern's testability pro/con

**"What could go wrong?"**
→ Read the Cons section carefully

---

**Next Steps:**
1. Pick a pattern that interests you
2. Read the dedicated section in DESIGN-PATTERNS.md
3. Find it in the actual code (reference in DESIGN-PATTERNS-CODE-EXAMPLES.md)
4. Run tests to see it in action
5. Try implementing something similar in your own project

