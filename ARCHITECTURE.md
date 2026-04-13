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
mvn spring-boot:run
```

Or run the packaged JAR directly:

```bash
java -jar target/customer-care-api-1.0.0-SNAPSHOT.jar
```

### 3 — Verify it's up

| Check | Command |
|-------|---------|
| Hello endpoint | `curl http://localhost:8080/hello` |
| Health probe | `curl http://localhost:8080/actuator/health` |
| Swagger UI | Open `http://localhost:8080/swagger-ui.html` in a browser |
| Raw OpenAPI spec | `curl http://localhost:8080/v3/api-docs` |

### Running with Redis (Iteration 2)

Once the Redis data layer is added, start Redis before the application:

```bash
docker compose up -d          # starts redis:7-alpine on port 6379
mvn spring-boot:run -Dspring-boot.run.profiles=local
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
| Web | Spring Web (Spring MVC) | (managed by Boot) |
| Data | Spring Data Redis | (managed by Boot) |
| Validation | Jakarta Bean Validation (Hibernate Validator) | (managed by Boot) |
| Boilerplate reduction | Lombok | 1.18.x |
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

> **Migration path:** The service layer is coded against a `AccountRepository` interface. Swapping from a Redis implementation to a JPA/Oracle implementation requires only a new `@Repository` class — no controller or service changes.

---

## 4. Project Structure

```
customer-care-api/
├── src/
│   ├── main/
│   │   ├── java/com/customercare/
│   │   │   ├── CustomerCareApplication.java            # Entry point (@SpringBootApplication)
│   │   │   │
│   │   │   ├── controller/
│   │   │   │   └── PaymentController.java              # REST controller
│   │   │   │
│   │   │   ├── service/
│   │   │   │   ├── PaymentService.java                 # Orchestration interface
│   │   │   │   ├── MatchCalculationService.java        # Match-tier interface
│   │   │   │   ├── DueDateCalculationService.java      # Due-date interface
│   │   │   │   └── impl/
│   │   │   │       ├── PaymentServiceImpl.java
│   │   │   │       ├── MatchCalculationServiceImpl.java
│   │   │   │       └── DueDateCalculationServiceImpl.java
│   │   │   │
│   │   │   ├── repository/
│   │   │   │   └── AccountRepository.java              # Interface for account data access
│   │   │   │
│   │   │   ├── repository/redis/
│   │   │   │   └── RedisAccountRepository.java         # Redis implementation
│   │   │   │
│   │   │   ├── model/
│   │   │   │   └── Account.java                        # @RedisHash domain object
│   │   │   │
│   │   │   ├── dto/
│   │   │   │   ├── request/
│   │   │   │   │   └── OneTimePaymentRequest.java
│   │   │   │   └── response/
│   │   │   │       ├── OneTimePaymentResponse.java
│   │   │   │       └── ErrorResponse.java
│   │   │   │
│   │   │   ├── exception/
│   │   │   │   ├── AccountNotFoundException.java
│   │   │   │   └── InvalidPaymentAmountException.java
│   │   │   │
│   │   │   ├── handler/
│   │   │   │   └── GlobalExceptionHandler.java         # @RestControllerAdvice
│   │   │   │
│   │   │   ├── util/
│   │   │   │   └── MoneyUtils.java                     # Rounding helpers
│   │   │   │
│   │   │   └── config/
│   │   │       └── RedisConfig.java                    # RedisTemplate, connection config
│   │   │
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── application-local.yml
│   │       └── application-prod.yml
│   │
│   └── test/
│       └── java/com/customercare/
│           ├── service/
│           │   ├── MatchCalculationServiceTest.java
│           │   └── DueDateCalculationServiceTest.java
│           ├── controller/
│           │   └── PaymentControllerIntegrationTest.java
│           └── util/
│               └── MoneyUtilsTest.java
│
├── docker-compose.yml          # Redis for local development
├── ARCHITECTURE.md
├── README.md
└── pom.xml
```

### Key simplifications vs. a full JPA architecture

- **No separate `User` entity** — the README only needs an account with a balance. A `userId` field on `Account` is sufficient. A `User` entity can be introduced when authentication/profile features are added.
- **No `Payment` audit entity** — the README asks to return the updated balance, not to persist a payment log. An audit table is a production concern for the Oracle iteration.
- **Single `AccountRepository` interface** — swappable implementations (Redis now, JPA/Oracle later) without touching business logic.

### Layering rules

```
Controller → Service → Repository (interface) → Redis / Oracle
                ↑
           Model / DTOs / Utils (shared horizontally)
```

- **Controllers** translate HTTP ↔ DTOs only; no business logic.
- **Services** own all business rules; they depend on repository interfaces, not implementations.
- **Repository interface** defines the contract; implementations are injected by Spring.
- **Model objects** are never returned directly from controllers — always mapped to a DTO.

---

## 5. Domain Model

### Iteration 1 — Redis

A single `Account` stored as a Redis Hash under key `account:{userId}`.

#### `Account` (`@RedisHash("account")`)

| Field | Type | Redis Key | Notes |
|-------|------|-----------|-------|
| `userId` | `String` | `@Id` — becomes the hash key | Primary identifier |
| `balance` | `BigDecimal` | stored as `String` in Redis | Scale 2, `>= 0` |

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
  "userId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "previousBalance": 100.00,
  "paymentAmount": 10.00,
  "matchPercentage": 3,
  "matchAmount": 0.30,
  "newBalance": 89.70,
  "nextPaymentDueDate": "2022-03-29"
}
```

**`OneTimePaymentResponse` fields:**

| Field | Type | Description |
|-------|------|-------------|
| `userId` | `String` | Echo of the requesting user |
| `previousBalance` | `BigDecimal` | Balance before this payment |
| `paymentAmount` | `BigDecimal` | The submitted payment |
| `matchPercentage` | `int` | Applied match tier (1, 3, or 5) |
| `matchAmount` | `BigDecimal` | Dollar value of the match |
| `newBalance` | `BigDecimal` | Balance after payment + match deduction |
| `nextPaymentDueDate` | `LocalDate` (ISO-8601 string) | Weekend-adjusted due date |

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

### 7.3 `PaymentService` (Orchestrator)

Controls the full one-time payment flow.

```
interface PaymentService {
    OneTimePaymentResponse processOneTimePayment(OneTimePaymentRequest request);
}
```

**Step-by-step flow:**

```
1. Validate: paymentAmount > 0         (guard clause → InvalidPaymentAmountException)
2. Fetch Account by userId from Redis  (not found → AccountNotFoundException)
3. Snapshot balanceBefore = account.getBalance()
4. matchAmount     = matchCalcService.calculateMatchAmount(paymentAmount)
5. totalDeduction  = paymentAmount.add(matchAmount)
6. newBalance      = balanceBefore.subtract(totalDeduction).setScale(2, HALF_UP)
7. nextDueDate     = dueDateService.calculateDueDate(LocalDate.now())
8. account.setBalance(newBalance)
   accountRepository.save(account)     // writes back to Redis
9. Build and return OneTimePaymentResponse
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
| 2022-05-08 | 2022-05-24 (Mon) | +15 = Sun → +1 |
| Any weekday | weekday + 15 | No shift |

### 9.3 Integration Tests — `PaymentControllerIntegrationTest`

Use `@SpringBootTest(webEnvironment = RANDOM_PORT)` with embedded Redis. Seed test data via a `@BeforeEach` setup method that writes accounts directly to Redis.

Key scenarios:

1. **Happy path — mid-tier match** — $10 payment, $100 balance → $89.70, 2022-03-29.
2. **Happy path — high-tier match** — $75 payment, $500 balance → $421.25, 2022-04-25.
3. **Happy path — low-tier match** — payment of $5 on a $50 balance.
4. **Weekend shift** — payment on a date whose +15 falls on a Saturday, assert Monday is returned.
5. **Validation failure** — `paymentAmount = 0` → `400`.
6. **Validation failure** — `paymentAmount = -1` → `400`.
7. **Account not found** — unknown `userId` → `404`.

### 9.4 Coverage Gate

JaCoCo is configured to fail the build if line coverage drops below **80%** in the `service` and `util` packages.

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
version: "3.9"
services:
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    command: redis-server --appendonly yes
```

### `pom.xml` — Key Dependencies

```xml
<!-- Spring Boot Parent -->
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>3.2.5</version>
</parent>

<dependencies>
  <!-- Web -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
  </dependency>

  <!-- Redis -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
  </dependency>

  <!-- Validation -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
  </dependency>

  <!-- Actuator (health, metrics) -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
  </dependency>

  <!-- Lombok -->
  <dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <optional>true</optional>
  </dependency>

  <!-- Embedded Redis (test only) -->
  <dependency>
    <groupId>com.github.codemonstur</groupId>
    <artifactId>embedded-redis</artifactId>
    <version>1.4.3</version>
    <scope>test</scope>
  </dependency>

  <!-- Testing -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
    <!-- Includes JUnit 5, Mockito, MockMvc, AssertJ -->
  </dependency>
</dependencies>

<!-- JaCoCo coverage plugin -->
<plugin>
  <groupId>org.jacoco</groupId>
  <artifactId>jacoco-maven-plugin</artifactId>
  <version>0.8.11</version>
</plugin>
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
| `LocalDate.now()` in service | Simple; acceptable for a first implementation | Inject a `Clock` bean for full testability |
| Separate `MatchCalculationService` and `DueDateCalculationService` | Single Responsibility; each is independently unit-testable and replaceable | Inline logic in `PaymentService` — harder to test in isolation |
| `@RestControllerAdvice` global handler | Centralised error translation; controllers stay clean | Per-controller `@ExceptionHandler` — more boilerplate |
| `userId` in request body | Clear and explicit; works without authentication infrastructure | Path variable (`/users/{userId}/one-time-payment`) — cleaner REST but requires URL routing decisions |
| Embedded Redis for tests | No Docker dependency in CI; tests run anywhere Maven runs | Testcontainers — closer to prod but heavier setup |

