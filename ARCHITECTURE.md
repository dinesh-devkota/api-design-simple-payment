# Architecture Document — `customer-care-api`

> **Service:** One-Time Payment API
> **Version:** 1.0.0
> **Last Updated:** April 2026

---

## Table of Contents

1. [Overview](#1-overview)
2. [Getting Started](#2-getting-started)
3. [Technology Stack](#3-technology-stack)
4. [Project Structure](#4-project-structure)
5. [Domain Model](#5-domain-model)
6. [API Contract](#6-api-contract)
7. [Business Logic](#7-business-logic)
8. [Error Handling](#8-error-handling)
9. [Testing Strategy](#9-testing-strategy)
10. [Configuration](#10-configuration)
11. [Observability & Logging](#11-observability--logging)
12. [Idempotency](#12-idempotency)
13. [Concurrency & Transaction Safety](#13-concurrency--transaction-safety)

---

## 1. Overview

`customer-care-api` is a Spring Boot REST microservice that exposes payment operations for the Customer Care domain. Its first feature is the **one-time payment** — allowing a user to make an ad-hoc payment toward their outstanding medical-expense balance outside of their regular payroll or bank-draft schedule.

### Core Behaviour

When a one-time payment is submitted:

1. The payment amount is deducted from the user's current balance.
2. A **match** — a percentage of the payment amount — is additionally deducted from the balance based on the payment tier (see §6.1).
3. A **next payment due date** is calculated as 15 calendar days from the date of payment.
4. If the calculated due date falls on a **Saturday or Sunday**, it is moved forward to the following **Monday**.
5. The updated balance and the due date are returned to the caller.

### Worked Examples

| Date of Payment | Starting Balance | Payment | Match | Match Amount | New Balance | Due Date (raw) | Due Date (adjusted) |
|----------------|-----------------|---------|-------|-------------|------------|----------------|---------------------|
| 2022-03-14     | $100.00          | $10.00  | 3%    | $0.30       | $89.70     | 2022-03-29 (Tue) | 2022-03-29          |
| 2022-04-08     | $500.00          | $75.00  | 5%    | $3.75       | $421.25    | 2022-04-23 (Sat) | 2022-04-25 (Mon)    |

> **Trade-off note:** The "match" reduces the remaining balance just like the payment itself — it is not an addition to what the user owes.

---

## 2. Getting Started

### Prerequisites

| Tool | Version | Check | Notes |
|------|---------|-------|-------|
| Java (JDK) | 21 | `java -version` | Must be a full JDK, not just JRE |
| Maven | 3.9.x | `mvn -version` | — |
| Docker Desktop | 4.x+ | `docker info` | Provides Docker Engine + Compose. **Redis runs as a Docker container — no separate Redis install required.** Must be running before `docker compose up`. |

> **Windows / Mac:** Docker Desktop must be running as an application before any `docker` commands will work. If `docker info` returns an error, launch Docker Desktop from the Start menu / Applications folder first.

### 1 — Clone & build

```bash
git clone <repo-url>
cd customer-care-api
mvn clean verify
```

`mvn verify` compiles, runs all tests, and generates the JaCoCo coverage report under `target/site/jacoco/`.

### 2 — Run locally

```bash
mvn spring-boot:run -pl bootstrap
```

Or run the packaged fat JAR directly:

```bash
java -jar bootstrap/target/bootstrap-1.0.0-SNAPSHOT.jar
```

### 3 — Verify it's up

| Check | Command |
|-------|---------|
| Hello endpoint | `curl http://localhost:8080/hello` |
| Health probe | `curl http://localhost:8080/actuator/health` |
| Swagger UI | Open `http://localhost:8080/swagger-ui.html` in a browser |
| Raw OpenAPI spec | `curl http://localhost:8080/v3/api-docs` |

### Running with Redis

Start Docker Desktop, then:

```bash
docker compose up -d          # starts redis:7-alpine on port 6379 + RedisInsight on port 5540
mvn spring-boot:run -pl bootstrap -Dspring-boot.run.profiles=local
```

`docker compose up -d` starts two containers (from `docker-compose.yml`):

| Container | Image | Port | Purpose |
|-----------|-------|------|---------|
| `customer-care-redis` | `redis:7-alpine` | `6379` | Redis data store |
| `customer-care-redisinsight` | `redis/redisinsight:latest` | `5540` | Browser-based Redis GUI |

**RedisInsight** — open `http://localhost:5540`, click **Add Redis Database**, set host `127.0.0.1` port `6379` to inspect keys live while the API runs.

> **Seed demo accounts before using Swagger** — run the seed script once after `docker compose up -d`:
>
> ```bash
> bash scripts/seed-local-data.sh
> ```
>
> | `userId` | Balance | Best example payment |
> |----------|---------|---------------------|
> | `user-001` | `$100.00` | `$10.00` → 3% match → new balance `$89.70` |
> | `user-002` | `$500.00` | `$75.00` → 5% match → new balance `$421.25` |
> | `user-low` | `$50.00` | `$5.00` → 1% match → new balance `$44.95` |
>
> The script uses `docker exec redis-cli` — no seed data ever touches Java source code.

To stop all containers:

```bash
docker compose down
```

> **Tests never need Docker** — `mvn verify` uses embedded Redis and runs anywhere Maven runs.

---

## 3. Technology Stack

### Iteration 1 (Current — Redis)

| Layer | Technology | Version |
|-------|-----------|---------|
| Language | Java | 21 (LTS) |
| Framework | Spring Boot | 3.2.x |
| Build Tool | Maven | 3.9.x |
| Architecture | Hexagonal (Ports & Adapters) | — |
| Web | Spring Web (Spring MVC) | (managed by Boot) |
| Data | Spring Data Redis | (managed by Boot) |
| Validation | Jakarta Bean Validation (Hibernate Validator) | (managed by Boot) |
| Boilerplate reduction | Lombok | 1.18.x |
| Mapping | MapStruct | 1.5.x |
| Data Store | Redis | 7.x |
| Embedded Redis (test) | `com.github.codemonstur:embedded-redis` | 1.4.x |
| Unit Testing | JUnit 5 + Mockito | (managed by Boot) |
| Web Layer Testing | Spring MockMvc | (managed by Boot) |
| Code Coverage | JaCoCo | 0.8.x |

### Future Iteration (Production — Oracle)

| Layer | Technology | Version |
|-------|-----------|---------|
| Persistence | Spring Data JPA + Hibernate | (managed by Boot) |
| Database | Oracle Database | 19c / 21c |
| Migration | Flyway | 9.x |

### Why Redis first?

- **Simplicity** — The README requires a single endpoint with user balance lookups. Redis key-value access (`account:{userId}`) is the simplest possible persistence for this use case — no schema definitions, no ORM configuration, no entity mappings.
- **Zero schema management** — No DDL, no migrations, no Hibernate `ddl-auto` surprises. Data is stored as JSON hashes and is immediately queryable.
- **Speed** — Sub-millisecond reads/writes. For a payment lookup + update cycle this is ideal.
- **Low ceremony** — Spring Data Redis requires a `RedisTemplate` or `@RedisHash` annotation vs. JPA's `@Entity`, `@Table`, `@Column`, `@GeneratedValue`, relationship mappings, etc.
- **Easy local development** — Embedded Redis for tests, Docker `redis:7-alpine` for local dev (single `docker run` command).

### Why Oracle later?

- **Audit trail** — Production payment systems need durable, ACID-compliant transaction logs. Oracle excels at this.
- **Compliance** — Relational schema with foreign keys enforces data integrity that Redis cannot guarantee.
- **Reporting** — SQL-based analytics on payment history, match distributions, and balance trends.
- **Existing infrastructure** — Many enterprise environments already run Oracle with DBA support, backups, and monitoring in place.

> **Migration path:** The domain defines an `AccountSpi` output port. The infra module provides the
> Redis-backed implementation. Swapping to JPA/Oracle requires only a new infra adapter class —
> the domain and app modules are untouched.

---

## 4. Project Structure

The project follows **Hexagonal Architecture** (Ports & Adapters) and is split into four Maven modules. Each module has strict dependency rules enforced by Maven's build reactor.

```
customer-care-api/                          ← parent POM (packaging: pom)
│
├── domain/                                 ← Pure business logic — no framework, no infra
│   └── src/main/java/com/customercare/domain/
│       ├── model/
│       │   └── Account.java                # Plain domain object (no Redis annotations)
│       ├── payment/
│       │   ├── ProcessPaymentUseCase.java  # Primary port (inbound use-case interface)
│       │   ├── ProcessPaymentService.java  # Use-case implementation (@Service)
│       │   └── PaymentResult.java          # Immutable value object (record)
│       ├── service/
│       │   ├── MatchCalculationService.java
│       │   ├── DueDateCalculationService.java
│       │   └── impl/
│       │       ├── MatchCalculationServiceImpl.java
│       │       └── DueDateCalculationServiceImpl.java
│       ├── spi/
│       │   ├── AccountSpi.java             # Secondary port — persistence contract
│       │   └── IdempotencyStoreSpi.java    # Secondary port — idempotency key-value store
│       └── exception/
│           ├── AccountNotFoundException.java
│           ├── InsufficientBalanceException.java
│           └── InvalidPaymentAmountException.java
│
├── infra/                                  ← Infrastructure adapters — depends on domain only
│   └── src/main/java/com/customercare/infra/
│       ├── config/
│       │   └── RedisConfig.java            # RedisTemplate bean
│       └── redis/
│           ├── entity/
│           │   └── AccountEntity.java      # @RedisHash persistence entity
│           ├── mapper/
│           │   └── AccountEntityMapper.java  # MapStruct: AccountEntity ↔ Account
│           ├── repository/
│           │   └── AccountRedisRepository.java  # Spring Data CrudRepository
           └── adapter/
               ├── AccountAdapter.java          # Implements AccountSpi via Redis
               └── RedisIdempotencyStore.java   # Implements IdempotencyStoreSpi; 24-hour TTL
│
├── app/                                    ← Driving adapters — depends on domain only
│   ├── openapi.yaml                        # Single source of truth for the API contract
│   └── src/main/java/com/customercare/app/
│       ├── rest/
│       │   ├── HelloController.java        # Implements generated HealthApi
│       │   └── PaymentController.java      # Implements generated PaymentApi
│       ├── idempotency/
│       │   └── IdempotencyGuard.java       # Cache-check/execute/store abstraction
│       ├── mapper/
│       │   └── PaymentResponseMapper.java  # MapStruct: PaymentResult → OneTimePaymentResponse
│       └── handler/
│           └── GlobalExceptionHandler.java # @RestControllerAdvice
│
└── bootstrap/                              ← Spring Boot entry point — wires all modules
    └── src/
        ├── main/
        │   ├── java/com/customercare/
        │   │   ├── CustomerCareApplication.java  # @SpringBootApplication
        │   │   └── config/
        │   │       └── ClockConfig.java          # Clock bean (fixed or system)
        │   └── resources/
        │       ├── application.yml
        │       ├── application-local.yml
        │       └── application-prod.yml
        └── test/
            └── java/com/customercare/
                ├── rest/
                │   └── HelloControllerTest.java               # @WebMvcTest
                └── controller/
                    └── PaymentControllerIntegrationTest.java  # @SpringBootTest + embedded Redis

scripts/
└── seed-local-data.sh                      ← One-time Redis seed for local dev (outside production code)
```

### Module dependency rules

```
bootstrap → app + infra + domain   (wires everything)
app       → domain                 (controllers call use-case ports; no infra knowledge)
infra     → domain                 (adapter implements domain SPI; no app/DTO knowledge)
domain    → (nothing internal)     (pure business logic; no Spring Data, no HTTP)
```

OpenAPI-generated sources (`com.customercare.api.*`, `com.customercare.dto.*`) are produced
during `mvn generate-sources` from `app/openapi.yaml` and are visible only inside the `app` module.

### Hexagonal layering

```
          ┌─────────────────────────────┐
          │         bootstrap           │  @SpringBootApplication — wires all beans
          └─────────────┬───────────────┘
                        │
          ┌─────────────▼───────────────┐
          │    app  (driving adapter)   │  REST controllers, mappers, exception handler
          │    Generated contract DTOs  │  (com.customercare.dto — only here)
          └─────────────┬───────────────┘
                        │ ProcessPaymentUseCase (primary port)
          ┌─────────────▼───────────────┐
          │         domain              │  Business rules — no framework, no infra
          │  ProcessPaymentService      │
          │  MatchCalculationService    │
          │  DueDateCalculationService  │
          │  AccountSpi (secondary port)│
          └─────────────┬───────────────┘
                        │ AccountSpi (implemented by)
          ┌─────────────▼───────────────┐
          │  infra  (driven adapter)    │  Redis entity, mapper, repository, adapter
          └─────────────────────────────┘
```

---

## 5. Domain Model

### Iteration 1 — Redis

A single `Account` stored as a Redis Hash under key `account:{userId}`.

#### `Account` (domain model — plain POJO)

| Field | Type | Notes |
|-------|------|-------|
| `userId` | `String` | Unique user identifier |
| `balance` | `BigDecimal` | Current outstanding balance (scale 2) |

The Redis persistence entity (`AccountEntity` in the `infra` module) carries `@RedisHash("account")` and `@Id`. MapStruct's `AccountEntityMapper` translates between the two.

**Redis storage example:**

```
HSET account:3fa85f64-5717-4562-b3fc-2c963f66afa6 balance "100.00"
```

> **Trade-off:** Redis stores `BigDecimal` as a string. This is fine for reads/writes but means no server-side arithmetic. All math happens in the Java service layer, which is where it belongs anyway.

### Future Iteration — Oracle

When migrating to Oracle, the model expands to support full audit and relational integrity:

#### `Account` (table: `accounts`)

| Field | Type | Notes |
|-------|------|-------|
| `id` | `UUID` | Primary key, generated |
| `userId` | `UUID` | FK → `users.id`, not null |
| `balance` | `NUMBER(19,2)` | Not null, `>= 0` |
| `updatedAt` | `TIMESTAMP` | Audit timestamp |

#### `Payment` (table: `payments`)

| Field | Type | Notes |
|-------|------|-------|
| `id` | `UUID` | Primary key, generated |
| `accountId` | `UUID` | FK → `accounts.id` |
| `paymentAmount` | `NUMBER(19,2)` | Amount submitted by the caller |
| `matchAmount` | `NUMBER(19,2)` | Match amount applied |
| `balanceBefore` | `NUMBER(19,2)` | Snapshot before |
| `balanceAfter` | `NUMBER(19,2)` | Snapshot after |
| `nextDueDate` | `DATE` | Weekend-adjusted due date |
| `createdAt` | `TIMESTAMP` | Audit timestamp |

### Entity Relationship (future state)

```
┌────────────┐        ┌─────────────┐
│  Account   │ 1    * │   Payment   │
│────────────│───────▶│─────────────│
│ id (PK)    │        │ id (PK)     │
│ userId     │        │ accountId   │
│ balance    │        │ paymentAmt  │
│ updatedAt  │        │ matchAmount │
└────────────┘        │ balanceBefore│
                      │ balanceAfter │
                      │ nextDueDate  │
                      └─────────────┘
```

---

## 6. API Contract

### `POST /one-time-payment`

#### Request

```
POST /one-time-payment
Content-Type: application/json
Idempotency-Key: <client-generated UUID>   (optional)
```

```json
{
  "userId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "paymentAmount": 10.00
}
```

**`OneTimePaymentRequest` fields:**

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `userId` | `String` | `@NotBlank` | Identifies the account to apply the payment to |
| `paymentAmount` | `BigDecimal` | `@NotNull`, `@DecimalMin("0.01")` | Payment amount in USD; must be > $0 |

**`Idempotency-Key` header** (optional): a client-generated unique string (UUID recommended). If supplied, the first successful response is cached for 24 hours. Subsequent requests with the same key return the cached response without reprocessing. See §12 for full details.

#### Response — `200 OK`

```json
{
  "previousBalance": 100.00,
  "newBalance": 89.70,
  "nextPaymentDueDate": "2022-03-29",
  "paymentDate": "2022-03-14"
}
```

**`OneTimePaymentResponse` fields:**

| Field | Type | Description |
|-------|------|-------------|
| `previousBalance` | `BigDecimal` | Account balance before the payment was applied |
| `newBalance` | `BigDecimal` | Updated balance after payment + match deduction |
| `nextPaymentDueDate` | `LocalDate` (ISO-8601 string) | Weekend-adjusted due date, 15 days from the payment date |
| `paymentDate` | `LocalDate` (ISO-8601 string) | Server-recorded date the payment was processed |

#### HTTP Status Codes

| Status | Scenario |
|--------|----------|
| `200 OK` | Payment processed successfully |
| `400 Bad Request` | Validation failure (e.g. `paymentAmount <= 0`, missing `userId`) |
| `404 Not Found` | `userId` or associated account does not exist |
| `422 Unprocessable Entity` | Payment amount + match exceeds the account balance |
| `500 Internal Server Error` | Unexpected server-side error |

---

## 7. Business Logic

### 7.1 `MatchCalculationService`

Determines the match percentage tier and calculates the match amount.

```
interface MatchCalculationService {
    int    getMatchPercentage(BigDecimal paymentAmount);
    BigDecimal calculateMatchAmount(BigDecimal paymentAmount);
}
```

**Tier table:**

| Condition | Match % |
|-----------|---------|
| `0 < paymentAmount < 10` | 1% |
| `10 <= paymentAmount < 50` | 3% |
| `paymentAmount >= 50` | 5% |

**Implementation logic (pseudo-code):**

```
if paymentAmount < 10   → matchPct = 1
if paymentAmount < 50   → matchPct = 3
else                    → matchPct = 5

matchAmount = paymentAmount
              .multiply(BigDecimal.valueOf(matchPct))
              .divide(BigDecimal.valueOf(100), 2, HALF_UP)
```

> All comparisons use `BigDecimal.compareTo()` — never `==` or `equals()`.

### 7.2 `DueDateCalculationService`

Computes the next payment due date and applies the weekend shift.

```
interface DueDateCalculationService {
    LocalDate calculateDueDate(LocalDate paymentDate);
}
```

**Logic:**

```
rawDueDate = paymentDate.plusDays(15)

switch rawDueDate.getDayOfWeek():
    SATURDAY → return rawDueDate.plusDays(2)   // → Monday
    SUNDAY   → return rawDueDate.plusDays(1)   // → Monday
    default  → return rawDueDate
```

**Examples (matching the parameterized unit test — consecutive 2026-04-10→16 block):**

| Payment Date | Raw +15 | Day | Adjusted Due Date |
|-------------|---------|-----|------------------|
| 2026-04-10 (Fri) | 2026-04-25 | Saturday | 2026-04-27 (Mon +2) |
| 2026-04-11 (Sat) | 2026-04-26 | Sunday | 2026-04-27 (Mon +1) |
| 2026-04-12 (Sun) | 2026-04-27 | Monday | 2026-04-27 (no shift) |
| 2026-04-13 (Mon) | 2026-04-28 | Tuesday | 2026-04-28 (no shift) |
| 2026-04-14 (Tue) | 2026-04-29 | Wednesday | 2026-04-29 (no shift) |
| 2026-04-15 (Wed) | 2026-04-30 | Thursday | 2026-04-30 (no shift) |
| 2026-04-16 (Thu) | 2026-05-01 | Friday | 2026-05-01 (no shift) |

### 7.3 `ProcessPaymentService` (Orchestrator)

Primary port implementation that controls the full one-time payment flow.

```
interface ProcessPaymentUseCase {
    PaymentResult process(String userId, BigDecimal paymentAmount);
}
```

**Step-by-step flow:**

```
1. Validate: paymentAmount > 0              (guard clause → InvalidPaymentAmountException)
2. Fetch Account via AccountSpi.findById()  (not found → AccountNotFoundException)
3. Snapshot previousBalance = account.getBalance()
4. matchAmount     = matchCalcService.calculateMatchAmount(paymentAmount)
5. totalDeduction  = paymentAmount.add(matchAmount)
6. Guard: totalDeduction > previousBalance  (→ InsufficientBalanceException)
7. newBalance      = previousBalance.subtract(totalDeduction).setScale(2, HALF_UP)
8. today           = LocalDate.now(clock)
   nextDueDate     = dueDateService.calculateDueDate(today)
9. account.setBalance(newBalance)
   accountSpi.save(account)
10. Build and return PaymentResult record
```

> **`Clock` injection:** `LocalDate.now(clock)` uses an injected `java.time.Clock` bean so unit tests can pin the date without touching production code. Production wires `Clock.systemDefaultZone()`. See §9.4 and the Appendix for details.

---

## 8. Error Handling

### Custom Exceptions

| Exception | HTTP Status | When Thrown |
|-----------|------------|-------------|
| `AccountNotFoundException` | 404 | `userId` not found in Redis |
| `InvalidPaymentAmountException` | 400 | `paymentAmount <= 0` |
| `InsufficientBalanceException` | 422 | `paymentAmount + matchAmount > balance` |

### `GlobalExceptionHandler`

Annotated with `@RestControllerAdvice`. Catches all known exceptions and maps them to a standardised `ErrorResponse`.

```json
{
  "timestamp": "2022-03-14T10:15:30Z",
  "status": 404,
  "error": "Not Found",
  "message": "Account not found for userId: 3fa85f64-5717-4562-b3fc-2c963f66afa6"
}
```

**`ErrorResponse` fields:**

| Field | Type | Description |
|-------|------|-------------|
| `timestamp` | `Instant` | When the error occurred |
| `status` | `int` | HTTP status code |
| `error` | `String` | HTTP status reason phrase |
| `message` | `String` | Human-readable description |
| `errors` | `List<String>` | Field-level constraint messages; populated only on `400` validation failures, absent otherwise |

A catch-all handler for `Exception.class` returns `500 Internal Server Error` with a generic message so that internal stack traces are never leaked to the client.

---

## 9. Testing Strategy

### Pyramid

```
         ▲
        / \
       / E2E\      (out of scope — covered by a separate QA suite)
      /──────\
     / Integ- \
    / ration   \   MockMvc @SpringBootTest (embedded Redis)
   /────────────\
  /  Unit Tests  \ JUnit 5 + Mockito (no Spring context)
 /────────────────\
```

### 9.1 Unit Tests — `MatchCalculationServiceTest`

Test every tier boundary with explicit `BigDecimal` values:

| Input | Expected Match % | Expected Match Amount |
|-------|-----------------|----------------------|
| `0.01` | 1% | `0.00` |
| `9.99` | 1% | `0.10` |
| `10.00` | 3% | `0.30` |
| `49.99` | 3% | `1.50` |
| `50.00` | 5% | `2.50` |
| `100.00` | 5% | `5.00` |

### 9.2 Unit Tests — `DueDateCalculationServiceTest`

Parameterized test covering all `DayOfWeek` outcomes using a consecutive one-week block (2026-04-10 → 2026-04-16). Because 15 mod 7 = 1 this block steps the result day-of-week forward by exactly one, guaranteeing all seven cases are hit:

| Payment Date | Raw +15 | Day of Week | Expected Due Date |
|-------------|---------|-------------|------------------|
| `2026-04-10` (Fri) | `2026-04-25` | Saturday | `2026-04-27` (Mon +2) |
| `2026-04-11` (Sat) | `2026-04-26` | Sunday | `2026-04-27` (Mon +1) |
| `2026-04-12` (Sun) | `2026-04-27` | Monday | `2026-04-27` (no shift) |
| `2026-04-13` (Mon) | `2026-04-28` | Tuesday | `2026-04-28` (no shift) |
| `2026-04-14` (Tue) | `2026-04-29` | Wednesday | `2026-04-29` (no shift) |
| `2026-04-15` (Wed) | `2026-04-30` | Thursday | `2026-04-30` (no shift) |
| `2026-04-16` (Thu) | `2026-05-01` | Friday | `2026-05-01` (no shift) |

### 9.3 Integration Tests — `PaymentControllerIntegrationTest`

Located in the `bootstrap` module (which depends on all other modules). Uses `@SpringBootTest(webEnvironment = RANDOM_PORT)` with embedded Redis started before the Spring context via `ApplicationContextInitializer`. Test data is seeded via `AccountRedisRepository`.

Key scenarios:

1. **Happy path — mid-tier match** — $10 payment, $100 balance → `newBalance = $89.70`, `nextPaymentDueDate` non-null.
2. **Happy path — high-tier match** — $75 payment, $500 balance → `newBalance = $421.25`, `nextPaymentDueDate` non-null.
3. **Happy path — low-tier match** — $5 payment, $50 balance → `newBalance = $44.95`.
4. **Weekend shift** — payment on a date whose +15 falls on a Saturday, assert Monday is returned.
5. **Validation failure** — `paymentAmount = 0` → `400`.
6. **Validation failure** — `paymentAmount = -1` → `400`.
7. **Account not found** — unknown `userId` → `404`.

### 9.4 Conditional Date for Testing

The service resolves "today" through an injected `java.time.Clock` bean rather than calling `LocalDate.now()` directly inside business logic.

| Context | Clock bean | Effective "today" |
|---------|-----------|-------------------|
| Unit tests (`ProcessPaymentServiceTest`) | `Clock.fixed(2026-04-13T12:00:00Z, UTC)` — also `2026-04-10` and `2026-04-11` for weekend-shift cases | `2026-04-13`, `2026-04-10`, `2026-04-11` respectively |
| Unit tests (`DueDateCalculationServiceTest`) | No clock needed — service takes a `LocalDate` input directly | Parameterized with 2026-04-10 … 2026-04-16 |
| Integration tests (`PaymentControllerIntegrationTest`) | No fixed clock — `nextPaymentDueDate` is asserted only to be **non-null** (exact date is validated at unit-test level) | Current system date at test run time |
| Production | `Clock.systemDefaultZone()` | Current system date |

> **Manual testing:** Set `app.fixed-date=YYYY-MM-DD` in your run configuration to activate a fixed clock at runtime (e.g. in Swagger UI). See §10 for exact Maven, IntelliJ, and JAR invocations.

- Due-date assertions in unit tests (e.g. "payment on `2026-04-13` + 15 = `2026-04-28` (Tuesday, no shift)") are **fully deterministic** — they never break because the calendar date changed.
- The fixed date block (`2026-04-10` → `2026-04-16`) was chosen because 15 mod 7 = 1, so successive days step neatly through all seven `DayOfWeek` outcomes in a single consecutive week.
- The `Clock` is injected via constructor in `ProcessPaymentService`, keeping the domain layer free of any test-only concerns.
- Integration tests deliberately avoid pinning `nextPaymentDueDate` to a specific value — the weekend-shift correctness is already proven by the unit tests; the integration layer only verifies the field is wired through end-to-end.

> **See also:** The Appendix design-decision table entry "Injected `Clock` in service" for the trade-off rationale.

### 9.5 Coverage Gate

JaCoCo (configured in the `bootstrap` module) fails the build if line coverage drops below **80%** in:

- `com.customercare.domain.service.*` (MatchCalculationService, DueDateCalculationService impls)
- `com.customercare.domain.payment` (ProcessPaymentService)

---

## 10. Configuration

### `application.yml` (base)

```yaml
spring:
  application:
    name: customer-care-api
  jackson:
    serialization:
      write-dates-as-timestamps: false   # ISO-8601 dates in JSON
    default-property-inclusion: non_null

server:
  port: 8080

logging:
  level:
    com.customercare: INFO
```

### `application-local.yml` (active profile: `local`)

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379

# Optional: pin the date for manual Swagger testing of weekend due-date shifts.
# Remove or comment out for normal local development.
# app.fixed-date: 2026-04-17   # Friday +15 = 2026-05-02 (Sat) → shifted to 2026-05-04 (Mon)
```

### Fixed-date run configurations

`ClockConfig` activates a fixed `Clock` bean whenever `app.fixed-date` is set. This lets you manually verify the weekend shift logic via Swagger UI without waiting for the right calendar day.

**IntelliJ Run Configuration (recommended on Windows — no quoting issues):**

1. Open **Run → Edit Configurations → + → Spring Boot**
2. **Main class:** `com.customercare.CustomerCareApplication`
3. **Active profiles:** `local`
4. **VM options:** `-Dapp.fixed-date=2026-04-17`
5. Click **Run**

**Maven — Mac / Linux:**

```bash
mvn spring-boot:run -pl bootstrap -Dspring-boot.run.profiles=local -Dspring-boot.run.jvmArguments="-Dapp.fixed-date=2026-04-17"
```


**Fat JAR:**

```bash
java -Dapp.fixed-date=2026-04-17 \
     -jar bootstrap/target/bootstrap-1.0.0-SNAPSHOT.jar \
     --spring.profiles.active=local
```

**Useful test dates** (all trigger a shift when +15 is applied):

| `app.fixed-date` | Day | Raw +15 | Lands on | Shifted to |
|-----------------|-----|---------|----------|------------|
| `2026-04-17` | Friday | `2026-05-02` | Saturday | `2026-05-04` (Mon) |
| `2026-04-18` | Saturday | `2026-05-03` | Sunday | `2026-05-05` (Mon) |
| `2026-04-19` | Sunday | `2026-05-04` | Monday | `2026-05-04` (no shift) |

> A `WARN` log line is emitted at startup when a fixed clock is active:
> `*** FIXED CLOCK ACTIVE — app.fixed-date=2026-04-17 (FRIDAY). Do NOT use in production. ***`

### `application-prod.yml` (active profile: `prod`)

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD}
      ssl:
        enabled: true
```

### `docker-compose.yml` (local development)

```yaml
services:
  redis:
    image: redis:7-alpine
    container_name: customer-care-redis
    ports:
      - "6379:6379"
    command: redis-server --appendonly yes
    volumes:
      - redis-data:/data
  redisinsight:
    image: redis/redisinsight:latest
    container_name: customer-care-redisinsight
    ports:
      - "5540:5540"
    depends_on:
      - redis

volumes:
  redis-data:
```

### `pom.xml` — Key Dependencies

The project uses a **parent POM** at the root with `<packaging>pom</packaging>` and four child modules. Dependencies are managed centrally in `<dependencyManagement>`.

```xml
<!-- Root parent POM -->
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>3.2.5</version>
</parent>
<packaging>pom</packaging>
<modules>
  <module>domain</module>
  <module>infra</module>
  <module>app</module>
  <module>bootstrap</module>
</modules>

<!-- domain module: spring-context, lombok, spring-boot-starter-test -->
<!-- infra  module: domain, spring-boot-starter-data-redis, mapstruct, lombok -->
<!-- app    module: domain, spring-boot-starter-web, spring-boot-starter-validation,
                    springdoc-openapi-starter-webmvc-ui, mapstruct, lombok -->
<!-- bootstrap module: domain + infra + app, spring-boot-starter-actuator,
                       embedded-redis (test), spring-boot-maven-plugin, jacoco -->
```

### Future: Oracle Dependencies (added during migration)

```xml
  <!-- JPA (replaces Redis for Oracle iteration) -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
  </dependency>

  <!-- Oracle JDBC Driver -->
  <dependency>
    <groupId>com.oracle.database.jdbc</groupId>
    <artifactId>ojdbc11</artifactId>
    <scope>runtime</scope>
  </dependency>

  <!-- Flyway (schema migrations) -->
  <dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-oracle</artifactId>
  </dependency>
```

---

## 11. Observability & Logging

### Log levels

| Package | Level | Rationale |
|---------|-------|-----------|
| `com.customercare` | `INFO` | Default for all application code |
| Framework internals | `WARN` | Spring Boot default |

Switch to `DEBUG` for verbose adapter-level output (Redis commands, mapper calls) without redeploying.

### Structured log events — `PaymentController`

Every request emits at least two INFO lines (three on an idempotency cache hit) that give ops full visibility without tailing DEBUG:

| Event | Level | Fields |
|-------|-------|--------|
| Request received | `INFO` | `userId`, `amount`, `idempotencyKey` (or `"(none)"`) |
| Idempotency cache hit | `INFO` | `idempotencyKey` |
| Payment completed | `INFO` | `userId`, `prevBalance`, `newBalance`, `dueDate`, `elapsedMs` |

Domain-level errors are logged at `WARN` (no stack trace); unexpected errors at `ERROR` (full stack trace, server-side only — never leaked to the client).

`GET /hello` emits a single `DEBUG` line — zero INFO noise at the health-check level.

### MDC correlation

`userId` is placed in the [Mapped Diagnostic Context](https://logback.qos.ch/manual/mdc.html) for the entire duration of a payment request and cleaned up in a `finally` block:

```java
MDC.put("userId", request.getUserId());
try { ... } finally { MDC.remove("userId"); }
```

Every log line emitted by downstream components (service, Redis adapter) automatically inherits this value. A single `userId` grep returns the complete trace for one request.

To include `userId` in log output, add `%X{userId}` to your Logback pattern:

```xml
<pattern>%d{ISO8601} %-5level [%X{userId}] %logger{36} - %msg%n</pattern>
```

---

## 12. Idempotency

### Design

Duplicate requests (network retries, double-clicks) are handled via an optional `Idempotency-Key` request header. The key is a client-generated string (UUID recommended) that uniquely identifies a logical operation.

The flow:

```
Request arrives with Idempotency-Key
│
├── Key found in cache  →  return cached response immediately (no reprocessing)
│
└── Key NOT in cache    →  process payment
                            store response in cache (24-hour TTL)
                            return fresh response
```

### Components

| Component | Location | Responsibility |
|-----------|----------|----------------|
| `IdempotencyStoreSpi` | `domain/spi` | Secondary port — defines `find(key, type)` and `store(key, value)`; no Redis knowledge |
| Redis adapter (impl) | `infra` | Implements `IdempotencyStoreSpi`; serialises response as JSON; applies 24-hour TTL |
| `IdempotencyGuard` | `app/idempotency` | Encapsulates the cache-check → execute → store pattern as a generic `resolve(key, type, supplier)` call |
| `PaymentController` | `app/rest` | Passes the header value to `IdempotencyGuard.resolve()` — has no direct knowledge of how caching works |

### Why a dedicated `IdempotencyGuard`?

Before the extraction, the controller contained two `if (key != null && !key.isBlank())` guards and the full cache lookup/store inline. Moving this to `IdempotencyGuard` means:

- The controller has a single responsibility: HTTP ↔ domain translation.
- Idempotency behaviour (TTL, error handling, serialisation format) can change without touching the controller.
- `IdempotencyGuard` is independently unit-testable with a mock `IdempotencyStoreSpi`.

---

## 13. Concurrency & Transaction Safety

### The core problem

`ProcessPaymentService.process()` performs a **read-modify-write** cycle:

```
1. GET   account = accountSpi.findById(userId)      ← read
2.       newBalance = balance - payment - match      ← compute (in-memory)
3. SET   accountSpi.save(account)                    ← write
```

Each individual Redis command (`GET`, `SET`) is atomic, but the **sequence** is not. If two concurrent requests for the same `userId` both execute step 1 before either reaches step 3, the second write silently overwrites the first — **one payment is lost**.

```
Thread A: GET balance=$100         ───────────────────────────────────────▶ SET balance=$90
Thread B:          GET balance=$100 ───────────────────▶ SET balance=$90
                                                        ↑ Thread A's deduction is lost
```

### Current mitigations

| Mechanism | Scope | What it protects |
|-----------|-------|------------------|
| `InsufficientBalanceException` guard | Per-request | Prevents balance going negative within a single request |
| `Idempotency-Key` header | Per-key | Prevents the **same** request from being processed twice (retries, double-clicks) |
| Single-user traffic assumption | Implicit | The exercise scenario assumes one user is not making concurrent payments |

Neither of these prevents two **different** concurrent requests for the **same user** from racing.

### Why this is acceptable for iteration 1

- The exercise is a single-endpoint coding exercise — not a production payment gateway. Concurrent payments for the same user are not in scope.
- Adding distributed locking or Redis transactions (WATCH/MULTI) would significantly increase implementation complexity without being required by the spec.
- The architecture already isolates persistence behind `AccountSpi`, so adding atomicity requires changing **only the infra adapter** — not the domain or app layers.

### Solutions for production (iteration 2+)

| Approach | Store | How it works |
|----------|-------|--------------|
| **Redis WATCH/MULTI** (optimistic) | Redis | `WATCH account:{userId}` → `GET` → compute → `MULTI` → `SET` → `EXEC`. If another client modified the key between `WATCH` and `EXEC`, the transaction aborts and can be retried. |
| **Redis Lua script** (atomic) | Redis | A single Lua script runs `GET`, computes the new balance, and `SET`s it atomically on the Redis server — no round-trip race window. |
| **Distributed lock** (pessimistic) | Redis | Acquire a lock on `payment-lock:{userId}` (e.g. Redisson `RLock`) before the read-modify-write, release in `finally`. Simple but adds latency and lock-management complexity. |
| **`@Transactional` + `SELECT … FOR UPDATE`** | Oracle/JPA | When migrating to Oracle, annotate `ProcessPaymentService.process()` with `@Transactional`. The JPA adapter performs `SELECT * FROM accounts WHERE user_id = ? FOR UPDATE`, which acquires a row-level lock. No concurrent transaction can read stale balance until the lock is released at commit. This is the standard approach for financial systems. |
| **Optimistic locking (`@Version`)** | Oracle/JPA | Add a `@Version` column to the `Account` entity. JPA includes `WHERE version = ?` in the `UPDATE`. If another transaction committed first, Hibernate throws `OptimisticLockException`, which the global exception handler can map to HTTP 409 (Conflict) for client retry. |

### Migration checklist (Redis → Oracle)

When moving to Oracle/JPA:

1. Add `@Transactional` to `ProcessPaymentService.process()`.
2. Use `SELECT … FOR UPDATE` (pessimistic) or `@Version` (optimistic) in the JPA adapter.
3. Configure a `PlatformTransactionManager` bean (auto-configured by `spring-boot-starter-data-jpa`).
4. Test for `OptimisticLockException` / deadlock scenarios in integration tests.

---

## Appendix — Design Decisions & Trade-offs

| Decision | Rationale | Alternative |
|----------|-----------|-------------|
| `BigDecimal` for all money | Avoids IEEE 754 floating-point errors in financial math | `double` — rejected due to precision loss |
| Redis for iteration 1 | Simplest persistence for key-value account lookups; zero schema; fast local dev | H2/JPA — more ceremony than needed for a single-endpoint demo |
| Oracle for production | ACID transactions, audit trail, SQL reporting, enterprise DBA support | PostgreSQL — viable but Oracle chosen to match existing infrastructure |
| Repository interface pattern | Enables Redis → Oracle swap by changing only the implementation class | Hardcoded Redis calls in service — blocks future migration |
| Single `Account` model (no `User` entity) | README only requires balance tracking by `userId`; YAGNI | Full `User` + `Account` — adds complexity with no current requirement |
| No `Payment` audit entity in iteration 1 | README asks for a response, not persistence of payment history | Persist every payment — deferred to Oracle iteration where it makes more sense |
| Injected `Clock` in service | Full testability — unit tests pin the date to `2026-04-13`; production uses `Clock.systemDefaultZone()` | `LocalDate.now()` — simpler but untestable for date-dependent logic |
| Separate `MatchCalculationService` and `DueDateCalculationService` | Single Responsibility; each is independently unit-testable and replaceable | Inline logic in `PaymentService` — harder to test in isolation |
| `@RestControllerAdvice` global handler | Centralised error translation; controllers stay clean | Per-controller `@ExceptionHandler` — more boilerplate |
| `userId` in request body | Clear and explicit; works without authentication infrastructure | Path variable (`/users/{userId}/one-time-payment`) — cleaner REST but requires URL routing decisions |
| Embedded Redis for tests | No Docker dependency in CI; tests run anywhere Maven runs | Testcontainers — closer to prod but heavier setup |
| No `@Transactional` (Redis iteration) | Spring's `@Transactional` wraps a `PlatformTransactionManager` — Redis has no such manager in the current stack. Individual Redis commands are atomic but the read-modify-write **sequence** is not (see §13). The race is accepted for exercise scope. **When migrating to Oracle/JPA**, add `@Transactional` to `ProcessPaymentService.process()` with `SELECT … FOR UPDATE` or `@Version` for true isolation. | Add `@Transactional` now — no transaction manager to back it; false sense of safety |
| `InsufficientBalanceException` guard | Prevents negative balances; returned as HTTP 422 (Unprocessable Entity) | Allow negative balance — risky in a financial domain |
| `Idempotency-Key` header | Protects against duplicate payment submissions (network retries, double-clicks). Cached responses stored in Redis with 24-hour TTL. | No idempotency — simpler but unsafe for production payment traffic |
| `IdempotencyGuard` extraction | Keeps controller lean — single responsibility (HTTP ↔ domain). Cache-check/execute/store logic is independently testable and changeable. | Inline `if (key != null)` guards in the controller — duplicated logic, mixed concerns |
| No concurrency guard on balance (Redis iteration) | Single-user traffic assumption for exercise scope. The read-modify-write race on `account:{userId}` is documented in §13 with four production-ready solutions. Hexagonal architecture ensures the fix lives in the infra adapter only. | Redis WATCH/MULTI, Lua script, or distributed lock — all add significant complexity with no exercise requirement |

