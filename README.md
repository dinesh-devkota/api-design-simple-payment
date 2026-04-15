# Welcome!
We're really excited about you joining our team! We designed this exercise to give you a taste of the challenges you may encounter in the role, and understand what it would be like to work closely together.


## About this exercise
You should expect to:

* Spend a few hours of your time on this exercise. We want to understand your thought process, so feel free to comment your code with trade-offs you made or other considerations you would have taken given different time constraints
* About two days turnaround time for feedback from our team (except for weekends!)

We're looking for the following skills:

* Software development fundamentals (we want to see an understanding of test automation, code reuse, and the fundamentals of whichever programming language you choose).
* System design (we're looking for logical method structure, domain modeling, and use of design patterns).
* Communication (your code should be easy to understand and reason about).


## Instructions

1. To get started follow the [task](#task) below.
2. Once you are ready to submit commit and push your changes with `git commit` and `git push`.
3. Return to the landing page you were originally provided (should be in your email), check that the latest commit we have from you is accurate, and then click the submit button. This will notify the engineers on our team that your work is ready for review.

## Task
One of the most important things Paytient does is give our users control over how they pay for their out-of-pocket medical expenses. Usually this happens through payroll deductions or automatic bank drafts, but imagine you are a new engineer on our Payments team and that after months of feature request upvotes, we are finally going to implement one-time payments.

Your objective is to write a new API endpoint, POST /one-time-payment, that accepts any payment amount greater than $0 and returns a user's updated balance and their next payment due date, which is always 15 days into the future. We are also experimenting with a new matching program where Paytient will apply a "match" payment as a percentage of the one-time payment to the remaining balance. Use the table below to determine what match to apply for a tiered system of one-time payments.

| One-time Payment Amount | Match |
|-------------------------|-------|
| 0 < x < 10              | 1%    |
| 10 <= x < 50            | 3%    |
| X >= 50                 | 5%    |

Lastly, no one can have a payment due on a weekend (since that's a great way to ruin a weekend), so any payment due dates that fall on a Saturday or Sunday should be moved out to the next Monday.

For example, imagine someone with a $100 balance that makes a $10 one-time payment on March 14, 2022. They will have a new balance of $89.70 (their $10 payment plus 3% of $10, which is $0.30) and a next payment due date of March 29, 2022.

Similarly, someone with a $500 balance that makes a $75 one-time payment on April 8, 2022 will have a new balance of $421.25 (their $75 payment plus 5% of $75, which is $3.75) and a next payment due date of April 25, 2022.

---

## Implementation

### How to run

**Prerequisites — install these first:**

| Tool | Version | Download | Notes |
|------|---------|----------|-------|
| JDK 21 | 21 (LTS) | [Adoptium](https://adoptium.net/) | Must be JDK, not just JRE |
| Maven | 3.9+ | [maven.apache.org](https://maven.apache.org/download.cgi) | Or use the `mvnw` wrapper if present |
| Docker Desktop | 4.x+ | [docker.com/products/docker-desktop](https://www.docker.com/products/docker-desktop/) | Provides Docker Engine + Compose; **Redis runs inside Docker — no separate Redis install needed** |

> **Windows / Mac:** Docker Desktop must be running before `docker-compose up -d`. Check with `docker info` — if it errors, start Docker Desktop first.

```bash
# 1. Start Redis (and RedisInsight GUI) via Docker Desktop
docker-compose up -d

# 2. Seed demo accounts into Redis (run once)
bash scripts/seed-local-data.sh
```

**Step 3 — Start the service**

**Recommended on Windows / IntelliJ (avoids all command-line quoting issues):**

1. Open **Run → Edit Configurations → `+` → Spring Boot**
2. **Main class:** `com.customercare.CustomerCareApplication`
3. **Active profiles:** `local`
4. Click **Run** — no extra arguments needed

**For the weekend-shift demo** — instead of passing a command-line argument, just uncomment one line in `bootstrap/src/main/resources/application-local.yml` and restart:

```yaml
# Uncomment this line, restart, then POST /one-time-payment:
app.fixed-date: 2026-04-17
```

Re-comment it to go back to the real date. No command-line flags, no quoting issues.

**Mac / Linux terminal only:**
```bash
mvn spring-boot:run -pl bootstrap -Dspring-boot.run.profiles=local

# With fixed date (Mac/Linux only — Windows use the yml approach above):
mvn spring-boot:run -pl bootstrap -Dspring-boot.run.profiles=local -Dspring-boot.run.jvmArguments="-Dapp.fixed-date=2026-04-17"
```

**Weekend-shift demo** — with `app.fixed-date=2026-04-17` set, submit a payment for `user-001` and the response will show the shift:

```json
{
  "previousBalance": 100.00,
  "newBalance": 89.70,
  "nextPaymentDueDate": "2026-05-04",
  "paymentDate": "2026-04-17"
}
```

> `paymentDate` (Friday Apr 17) + 15 = `2026-05-02` (Saturday) → bumped to `2026-05-04` (Monday)

Service is available at `http://localhost:8080`.  
Interactive API docs: `http://localhost:8080/swagger-ui.html`

> **Swagger works immediately after seeding** — run `bash scripts/seed-local-data.sh` once after step 1. The Swagger examples are pre-filled with these user IDs:
>
> | `userId` | Starting balance | Try this payment |
> |----------|-----------------|-----------------|
> | `user-001` | `$100.00` | `paymentAmount: 10.00` → new balance `$89.70` (3% match) |
> | `user-002` | `$500.00` | `paymentAmount: 75.00` → new balance `$421.25` (5% match) |
> | `user-low` | `$50.00` | `paymentAmount: 5.00` → new balance `$44.95` (1% match) |
>
> Re-run the seed script any time to reset balances.

#### Redis Insight (GUI)

`docker-compose up -d` also starts **RedisInsight**, a browser-based Redis GUI.

1. Open `http://localhost:5540` in your browser.
2. Click **Add Redis Database**.
3. Fill in the connection details:
   - **Host:** `127.0.0.1`
   - **Port:** `6379`
   - **Database alias:** anything you like (e.g. `customer-care-local`)
4. Click **Add Redis Database** to confirm.

You can now browse keys, run raw commands, and inspect the data stored by the API.

```bash
# Run all tests (unit + integration; no Docker needed — tests use embedded Redis)
mvn verify
```

### Logging

Both controllers emit structured log lines at clearly defined points so production deployments are never blind:

| Event | Level | Fields logged |
|-------|-------|---------------|
| Request received (`POST /one-time-payment`) | `INFO` | `userId`, `amount`, `idempotencyKey` presence |
| Idempotency cache hit | `INFO` | `idempotencyKey`, `cachedNewBalance`, `elapsedMs` |
| Payment completed | `INFO` | `userId`, `prevBalance`, `newBalance`, `dueDate`, `elapsedMs` |
| Validation / domain errors | `WARN` | exception message (no stack trace leaked to client) |
| Unexpected errors | `ERROR` | full stack trace (server-side only) |
| Health check (`GET /hello`) | `DEBUG` | endpoint name |

**MDC correlation** — `userId` is placed in the [Mapped Diagnostic Context](https://logback.qos.ch/manual/mdc.html) for the entire duration of each payment request. Any log line emitted by downstream services (domain, infra adapter) automatically inherits this value, so a single `userId` grep pulls the complete trace for one request.

To include `userId` in every log line, add `%X{userId}` to your Logback pattern, e.g.:

```xml
<pattern>%d{ISO8601} %-5level [%X{userId}] %logger{36} - %msg%n</pattern>
```

Log levels are configured per-package in `application.yml` (`com.customercare: INFO`). Switch to `DEBUG` for verbose adapter-level output without redeploying.

### Architecture

The project uses **Hexagonal Architecture** across four Maven modules:

| Module      | Responsibility                                               |
|-------------|--------------------------------------------------------------|
| `domain`    | Pure business logic — match tiers, due-date rules, use-case |
| `app`       | REST controllers, mappers, global exception handler          |
| `infra`     | Redis adapter (`AccountAdapter`), Spring Data repository     |
| `bootstrap` | Spring Boot entry point, wires all modules together          |

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for the full diagram.

The **API contract** is defined in [`app/openapi.yaml`](app/openapi.yaml) (contract-first).  
Java DTOs and API interfaces are generated from it at compile time — do **not** edit them by hand.

### Endpoint

**`POST /one-time-payment`**

Request:
```json
{ "userId": "user-001", "paymentAmount": 10.00 }
```

Response `200 OK`:
```json
{
  "previousBalance": 100.00,
  "newBalance": 89.70,
  "nextPaymentDueDate": "2022-03-29",
  "paymentDate": "2022-03-14"
}
```

Error responses (`400`, `404`, `500`) use a standard envelope:
```json
{
  "timestamp": "2026-04-13T10:15:30Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "errors": ["paymentAmount: paymentAmount must be greater than 0"]
}
```

### Test coverage

See [`TEST_SCENARIOS.md`](TEST_SCENARIOS.md) for the full input/expected-output table for every test case.

> **Conditional date for testing:** The service resolves "today" via an injected `java.time.Clock` bean rather than calling `LocalDate.now()` directly. Unit tests pin the payment date to fixed dates in the `2026-04-10`–`2026-04-16` window so all seven `DayOfWeek` outcomes are deterministically verified. Integration tests only assert that `nextPaymentDueDate` is present (not a specific value), since exact due-date correctness is already proven at the unit-test layer. In production the application context wires `Clock.systemDefaultZone()` so the real current date is used.

