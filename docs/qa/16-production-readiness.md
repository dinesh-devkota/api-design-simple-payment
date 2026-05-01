# Q&A 16 — Production Readiness: What Is Missing?

> The gap between a working demo and a production-grade service. Senior engineers are expected to know this list without being asked.

---

## Q1: What security concerns are unaddressed?

### 1. No Authentication or Authorisation
The `POST /one-time-payment` endpoint accepts any request with no identity verification. In production:
- **Authentication** — verify the caller's identity (JWT bearer token, OAuth 2.0, mutual TLS).
- **Authorisation** — verify the caller is allowed to make a payment for the given `userId`. Currently `userId` comes from the request body — any caller can submit a payment for any user.

A user should only be allowed to pay against their own account. The `userId` should be extracted from a verified JWT claim, not from the request body.

### 2. No Input Sanitisation Beyond Bean Validation
The `userId` field accepts any string. In a SQL-backed system this would risk injection attacks. Even in Redis, a `userId` of `account:` (matching a Redis key prefix) could cause key collision. Add a whitelist validator (`^[a-zA-Z0-9_-]+$`).

### 3. Redis Password in `application-prod.yml` via Environment Variable
```yaml
password: ${REDIS_PASSWORD}
```
Good — it uses an environment variable rather than a hardcoded value. But the mechanism for delivering that secret to the runtime (Kubernetes Secret, AWS Secrets Manager, Vault) is not addressed.

### 4. No HTTPS Enforcement
The server runs on HTTP 8080. Production requires TLS termination (at the load balancer or within the service via `server.ssl.*` configuration).

### 5. No Rate Limiting
A client can submit unlimited payments per second, enabling balance-exhaustion attacks or accidental infinite-retry loops.

---

## Q2: What observability is missing?

### Distributed Tracing
The service emits structured logs with MDC `userId`, which is good for single-service debugging. But in a microservice landscape, a payment request might touch this service, a notification service, and an audit service. Without distributed tracing (OpenTelemetry / Zipkin / Jaeger), you cannot follow a request across service boundaries.

Add `spring-boot-starter-actuator` + `micrometer-tracing-bridge-otel` to propagate W3C `traceparent` headers and emit spans.

### Metrics
There are no application metrics:
- Payment success/failure rate.
- P50/P95/P99 latency per endpoint.
- Redis connection pool saturation.
- Idempotency cache hit rate.

Add Micrometer (`spring-boot-starter-actuator` + `micrometer-registry-prometheus`) to expose a `/actuator/prometheus` endpoint for Prometheus scraping.

### Alerting
No alert rules are defined. Production requires at minimum:
- Alert when error rate (`5xx` responses) exceeds a threshold.
- Alert when P99 latency exceeds SLA.
- Alert when Redis is unreachable.

### Health Checks
`/actuator/health` is available (Spring Boot Actuator is on the classpath). But there is no custom Redis health check beyond what Actuator provides. If Redis is down, the health endpoint should return `DOWN` so a load balancer can stop routing traffic.

---

## Q3: What reliability and resilience mechanisms are missing?

### No Circuit Breaker
If Redis becomes slow (not down, just slow — 2-second response times), every payment request blocks for 2 seconds. Under load, the thread pool exhausts and the service becomes completely unavailable.

Add Resilience4j circuit breaker around Redis calls:
```java
@CircuitBreaker(name = "redis", fallbackMethod = "fallback")
public Optional<Account> findById(String userId) { ... }
```

### No Timeout on Redis Operations
`application-prod.yml` configures `timeout: 2000` (2s) and `connect-timeout: 3000` (3s). These are set — this is good. But no circuit breaker means a sustained Redis slowdown still causes cascading failures.

### No Retry for Transient Failures
A transient Redis connection error (network blip) immediately fails the payment request. Spring Retry or Resilience4j Retry could transparently retry once or twice before propagating the error.

### No Graceful Shutdown
If the process is killed mid-request (e.g., during a Kubernetes rolling deploy), in-flight payments may be partially processed — balance deducted but response not sent. Spring Boot's `server.shutdown: graceful` setting waits for active requests to complete before the process exits.

---

## Q4: What data integrity issues exist?

### No Payment History / Audit Trail
Once a payment is processed, there is no record of it. If a user calls customer service asking "did my payment go through?", the only source of truth is the log files. A production payments system needs a `payments` table with every transaction, amount, timestamp, match applied, and resulting balance.

### No Balance History
The balance is a single mutable number in Redis. There is no way to reconstruct "what was the balance on March 14, 2022?" without replaying log files.

### No Double-Entry Bookkeeping
Real financial systems use double-entry accounting — every debit has a corresponding credit. This system has no debit/credit ledger, making it impossible to audit for consistency.

### Floating-Point Risk in Redis Storage
`BigDecimal balance` is stored as a string in Redis (e.g., `"100.00"`). If any other client writes a numeric value without proper formatting (e.g., `"100"` without the `.00`), Spring Data Redis may deserialize it incorrectly. Use strict serialization and validation on read.

---

## Q5: What operational concerns are missing?

### No Database Migration Strategy
Redis data is seeded manually via a shell script. In production, schema changes (e.g., adding a `createdAt` field to `AccountEntity`) have no migration path. For a relational database you'd use Flyway/Liquibase. For Redis, the convention is key versioning or a migration job.

### No Canary / Feature Flag Support
Deploying a change to the match tiers (e.g., changing 5% to 7%) requires a full redeployment. A feature flag system (LaunchDarkly, Unleash, Spring Cloud Config) would allow instant rollback without a deployment.

### No Configuration Hot-Reload
`app.fixed-date` requires a service restart to change. Using Spring Cloud Config Server would allow configuration changes without restarts.

### No Documentation for On-Call Engineers
There is no runbook: "if `/actuator/health` returns DOWN, here is what to check."

---

## Q6: What scalability limitations exist?

### Single Redis Node
`docker-compose.yml` starts one Redis node. A single node is a single point of failure and a write bottleneck. Production needs:
- **Redis Sentinel** (automatic failover) or **Redis Cluster** (horizontal partitioning).
- Read replicas for `findById` operations.

### No Horizontal Scaling of the Application
The application can run multiple instances (it is stateless in the HTTP layer), but the concurrent race condition in balance updates (Q&A 15) means multiple instances make the race worse, not better. Fix the concurrency issue before scaling horizontally.

### Idempotency TTL Is Arbitrary
24 hours is a reasonable default but not justified by any business requirement. If a client retries after 25 hours (e.g., a long-running batch job), the idempotency protection is gone and they may double-charge.

---

## Q7: What is missing from the CI/CD pipeline?

The `azure-pipelines.yml` runs `mvn verify` and publishes a JAR artifact. Missing:

| Missing stage | Why it matters |
|---|---|
| Static analysis (SpotBugs, PMD, Checkstyle) | Catches bugs and style violations before human review |
| SAST (Semgrep, SonarQube) | Identifies security vulnerabilities in source code |
| Dependency vulnerability scan (OWASP Dependency-Check) | Flags known CVEs in third-party libraries |
| Container image build + push | The JAR artifact is produced but no Docker image is built |
| Deployment stage | No automated deploy to staging/production |
| Smoke test after deploy | No post-deploy health check |
| JaCoCo coverage gate | Coverage report is generated but no minimum threshold enforced |

---

## Q8: What would a production-readiness checklist look like for this service?

```
Security
  [ ] Authentication (JWT/OAuth 2.0 on every endpoint)
  [ ] Authorisation (user can only pay against their own account)
  [ ] HTTPS enforced
  [ ] Rate limiting (per userId, per IP)
  [ ] Secret management (Vault, AWS Secrets Manager)
  [ ] Dependency vulnerability scan in CI

Reliability
  [ ] Circuit breaker on Redis calls
  [ ] Retry with exponential backoff for transient failures
  [ ] Graceful shutdown (server.shutdown: graceful)
  [ ] Atomic balance deduction (Lua script)
  [ ] Idempotency race-condition fix (SET NX PX)

Observability
  [ ] Distributed tracing (OpenTelemetry)
  [ ] Metrics endpoint (Micrometer + Prometheus)
  [ ] Alert rules (error rate, latency, Redis availability)
  [ ] Structured log format (JSON lines for log aggregator)
  [ ] Runbook for on-call engineers

Data Integrity
  [ ] Payment history table (immutable audit log)
  [ ] Balance history / ledger
  [ ] Database migration strategy

Scalability
  [ ] Redis Sentinel or Cluster
  [ ] Load test results documenting throughput limits
  [ ] Horizontal pod autoscaling policy

CI/CD
  [ ] Static analysis gate
  [ ] SAST gate
  [ ] Coverage minimum gate (e.g., 80%)
  [ ] Container image build and push
  [ ] Automated deployment to staging
  [ ] Post-deploy smoke test
```
