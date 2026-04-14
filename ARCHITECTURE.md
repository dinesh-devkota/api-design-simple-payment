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

| Tool | Version | Check |
|------|---------|-------|
| Java (JDK) | 21 | `java -version` |
| Maven | 3.9.x | `mvn -version` |
| Docker *(iteration 2 only)* | 20+ | `docker -version` |

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

Start Redis before the application:

```bash
docker compose up -d          # starts redis:7-alpine on port 6379
mvn spring-boot:run -pl bootstrap -Dspring-boot.run.profiles=local
```

To stop Redis:

```bash
docker compose down
```

> Tests always use **embedded Redis** — no Docker required to run `mvn verify`.

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
│       │   └── AccountSpi.java             # Secondary port (output port — persistence contract)
│       └── exception/
│           ├── AccountNotFoundException.java
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
│           └── adapter/
│               └── AccountAdapter.java     # Implements AccountSpi via Redis
│
├── app/                                    ← Driving adapters — depends on domain only
│   ├── openapi.yaml                        # Single source of truth for the API contract
│   └── src/main/java/com/customercare/app/
│       ├── rest/
│       │   ├── HelloController.java        # Implements generated HealthApi
│       │   └── PaymentController.java      # Implements generated PaymentApi
│       ├── mapper/
│       │   └── PaymentResponseMapper.java  # MapStruct: PaymentResult → OneTimePaymentResponse
│       └── handler/
│           └── GlobalExceptionHandler.java # @RestControllerAdvice
│
└── bootstrap/                              ← Spring Boot entry point — wires all modules
    └── src/
        ├── main/
        │   ├── java/com/customercare/
        │   │   └── CustomerCareApplication.java  # @SpringBootApplication
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

#### Response — `200 OK`

```json
{
  "newBalance": 89.70,
  "nextPaymentDueDate": "2022-03-29"
}
```

**`OneTimePaymentResponse` fields:**

| Field | Type | Description |
|-------|------|-------------|
| `newBalance` | `BigDecimal` | Updated balance after payment + match deduction |
| `nextPaymentDueDate` | `LocalDate` (ISO-8601 string) | Weekend-adjusted due date, always 15 days from today |

#### HTTP Status Codes

| Status | Scenario |
|--------|----------|
| `200 OK` | Payment processed successfully |
| `400 Bad Request` | Validation failure (e.g. `paymentAmount <= 0`, missing `userId`) |
| `404 Not Found` | `userId` or associated account does not exist |
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

**Examples:**

| Payment Date | Raw +15 | Day | Adjusted Due Date |
|-------------|---------|-----|------------------|
| 2022-03-14 | 2022-03-29 | Tuesday | 2022-03-29 |
| 2022-04-08 | 2022-04-23 | Saturday | 2022-04-25 (Mon) |
| 2022-05-01 | 2022-05-16 | Monday | 2022-05-16 |
| 2022-05-08 | 2022-05-23 | Sunday | 2022-05-24 (Mon) |

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
6. newBalance      = previousBalance.subtract(totalDeduction).setScale(2, HALF_UP)
7. nextDueDate     = dueDateService.calculateDueDate(LocalDate.now())
8. account.setBalance(newBalance)
   accountSpi.save(account)                 // persisted via infra adapter
9. Build and return PaymentResult record
```

> **Trade-off:** `LocalDate.now()` is called inside the service. In a production system this would be injected via a `Clock` bean to make unit tests deterministic.

---

## 8. Error Handling

### Custom Exceptions

| Exception | HTTP Status | When Thrown |
|-----------|------------|-------------|
| `AccountNotFoundException` | 404 | `userId` not found in Redis |
| `InvalidPaymentAmountException` | 400 | `paymentAmount <= 0` |

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

Test all `DayOfWeek` outcomes:

| Payment Date | Expected Due Date | Notes |
|-------------|-------------------|-------|
| 2022-03-14 (Mon) | 2022-03-29 (Tue) | No shift |
| 2022-04-08 | 2022-04-25 (Mon) | +15 = Sat → +2 |
| 2022-05-07 | 2022-05-23 (Mon) | +15 = Sun → +1 |
| Any weekday | weekday + 15 | No shift |

### 9.3 Integration Tests — `PaymentControllerIntegrationTest`

Located in the `bootstrap` module (which depends on all other modules). Uses `@SpringBootTest(webEnvironment = RANDOM_PORT)` with embedded Redis started before the Spring context via `ApplicationContextInitializer`. Test data is seeded via `AccountRedisRepository`.

Key scenarios:

1. **Happy path — mid-tier match** — $10 payment, $100 balance → $89.70, 2022-03-29.
2. **Happy path — high-tier match** — $75 payment, $500 balance → $421.25, 2022-04-25.
3. **Happy path — low-tier match** — payment of $5 on a $50 balance.
4. **Weekend shift** — payment on a date whose +15 falls on a Saturday, assert Monday is returned.
5. **Validation failure** — `paymentAmount = 0` → `400`.
6. **Validation failure** — `paymentAmount = -1` → `400`.
7. **Account not found** — unknown `userId` → `404`.

### 9.4 Coverage Gate

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
```

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
    ports:
      - "6379:6379"
    command: redis-server --appendonly yes
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

## Appendix — Design Decisions & Trade-offs

| Decision | Rationale | Alternative |
|----------|-----------|-------------|
| `BigDecimal` for all money | Avoids IEEE 754 floating-point errors in financial math | `double` — rejected due to precision loss |
| Redis for iteration 1 | Simplest persistence for key-value account lookups; zero schema; fast local dev | H2/JPA — more ceremony than needed for a single-endpoint demo |
| Oracle for production | ACID transactions, audit trail, SQL reporting, enterprise DBA support | PostgreSQL — viable but Oracle chosen to match existing infrastructure |
| Repository interface pattern | Enables Redis → Oracle swap by changing only the implementation class | Hardcoded Redis calls in service — blocks future migration |
| Single `Account` model (no `User` entity) | README only requires balance tracking by `userId`; YAGNI | Full `User` + `Account` — adds complexity with no current requirement |
| No `Payment` audit entity in iteration 1 | README asks for a response, not persistence of payment history | Persist every payment — deferred to Oracle iteration where it makes more sense |
| Injected `Clock` in service | Full testability — unit tests pin the date to `2022-03-14`; production uses `Clock.systemDefaultZone()` | `LocalDate.now()` — simpler but untestable for date-dependent logic |
| Separate `MatchCalculationService` and `DueDateCalculationService` | Single Responsibility; each is independently unit-testable and replaceable | Inline logic in `PaymentService` — harder to test in isolation |
| `@RestControllerAdvice` global handler | Centralised error translation; controllers stay clean | Per-controller `@ExceptionHandler` — more boilerplate |
| `userId` in request body | Clear and explicit; works without authentication infrastructure | Path variable (`/users/{userId}/one-time-payment`) — cleaner REST but requires URL routing decisions |
| Embedded Redis for tests | No Docker dependency in CI; tests run anywhere Maven runs | Testcontainers — closer to prod but heavier setup |
| No `@Transactional` (Redis iteration) | Redis single-key operations (`GET`, `SET`) are inherently atomic; a Spring `@Transactional` would add overhead with no benefit. **When migrating to Oracle/JPA**, add `@Transactional` to `ProcessPaymentService.process()` and configure a `PlatformTransactionManager`. | Add `@Transactional` now — unnecessary indirection for the current Redis backend |
| `InsufficientBalanceException` guard | Prevents negative balances; returned as HTTP 422 (Unprocessable Entity) | Allow negative balance — risky in a financial domain |
| `Idempotency-Key` header | Protects against duplicate payment submissions (network retries, double-clicks). Cached responses stored in Redis with 24-hour TTL. | No idempotency — simpler but unsafe for production payment traffic |

