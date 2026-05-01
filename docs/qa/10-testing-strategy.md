# Q&A 10 — Testing Strategy: Unit Tests, Integration Tests, and Embedded Redis

> Covers the test pyramid, each test class, embedded Redis, `@WebMvcTest`, fixed clocks, mocking, and improvements.

---

## Q1: What is the overall testing strategy for this project?

**A:** The project follows the **test pyramid** — more unit tests at the base, fewer integration tests at the top:

```
         ┌─────────────────────────┐
         │  Integration Tests (4)  │  Full Spring context + embedded Redis
         ├─────────────────────────┤
         │   Slice Test (1)        │  @WebMvcTest — Spring MVC layer only
         ├─────────────────────────┤
         │   Unit Tests (3 classes)│  Plain Java — no Spring, no Redis
         └─────────────────────────┘
```

| Test class | Layer | Spring? | Redis? |
|---|---|---|---|
| `MatchCalculationServiceTest` | Domain | No | No |
| `DueDateCalculationServiceTest` | Domain | No | No |
| `ProcessPaymentServiceTest` | Domain | No | No (mocked) |
| `HelloControllerTest` | App (slice) | Partial (`@WebMvcTest`) | No |
| `PaymentControllerIntegrationTest` | Full stack | Yes | Embedded |

---

## Q2: What does `@WebMvcTest` do and why is it used for `HelloControllerTest`?

**A:** `@WebMvcTest(HelloController.class)` starts a **Spring MVC slice** — a partial Spring context that loads only:
- `@Controller` / `@RestController` beans.
- `@ControllerAdvice` beans.
- Spring MVC infrastructure (Jackson, `MockMvc`).

It does **not** load `@Service`, `@Repository`, `@Component` beans or Redis/datasource auto-configuration. This makes it much faster than `@SpringBootTest` while still testing HTTP-layer concerns (routing, content negotiation, status codes).

`HelloController` has no dependencies (no injected beans), so a full Spring context is unnecessary. `@WebMvcTest` is the ideal fit.

---

## Q3: How does `ProcessPaymentServiceTest` test without Spring or Redis?

**A:** It uses **Mockito** to mock the only infrastructure dependency (`AccountSpi`), while using **real** implementations of the calculation services:

```java
@ExtendWith(MockitoExtension.class)
class ProcessPaymentServiceTest {
    @Mock
    private AccountSpi accountSpi;          // mocked — no Redis

    @BeforeEach
    void setUp() {
        useCase = new ProcessPaymentService(
                accountSpi,
                new MatchCalculationServiceImpl(), // real
                new DueDateCalculationServiceImpl(), // real
                FIXED_CLOCK);                      // fixed
    }
}
```

This tests the orchestration logic (`ProcessPaymentService`) with real tier and date calculations, while eliminating Redis I/O entirely. Tests run in milliseconds.

**Why use real calculation services instead of mocking them too?**
Mocking `MatchCalculationService` would mean the test only validates that `ProcessPaymentService` *calls* it, not that the entire calculation chain produces the correct result. Using real implementations verifies the end-to-end domain behaviour.

---

## Q4: How does the fixed `Clock` make date-based tests deterministic?

**A:**
```java
// Fixed to 2026-04-13 (Monday) — +15 = 2026-04-28 (Tuesday, no shift)
private static final Clock FIXED_CLOCK = Clock.fixed(
    ZonedDateTime.of(2026, 4, 13, 12, 0, 0, 0, ZoneId.of("UTC")).toInstant(),
    ZoneId.of("UTC"));
```

`LocalDate.now(clock)` inside `ProcessPaymentService` always returns `2026-04-13` when `FIXED_CLOCK` is injected. Without this, the test result would change depending on which day of the week it runs, and weekend-shift tests would only pass on specific days of the year.

Three clocks cover three scenarios:
- `FIXED_CLOCK` → Monday, +15 = Tuesday (no shift).
- `CLOCK_SATURDAY` → Friday, +15 = Saturday → shifted to Monday.
- `CLOCK_SUNDAY` → Saturday, +15 = Sunday → shifted to Monday.

---

## Q5: How does the integration test start an embedded Redis without Docker?

**A:** `PaymentControllerIntegrationTest` uses the `embedded-redis` library via a custom `ApplicationContextInitializer`:

```java
public static class RedisTestInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        redisServer = new RedisServer(6381);  // port 6381 avoids clashing with local 6379
        redisServer.start();
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "spring.data.redis.port=6381"); // override property BEFORE Spring context starts
    }
}
```

The initializer starts Redis **before** the Spring `ApplicationContext` is created — critical because Spring's auto-configured `RedisConnectionFactory` tries to connect during startup. If Redis started after the context, the connection would fail.

`@AfterAll` stops the embedded Redis after all tests complete.

---

## Q6: Why does the integration test seed data in `@BeforeEach` rather than `@BeforeAll`?

**A:**
```java
@BeforeEach
void setUp() {
    accountRedisRepository.deleteAll();  // clear any previous state
    accountRedisRepository.save(new AccountEntity("user-001", new BigDecimal("100.00")));
    // ...
}
```

`@BeforeAll` runs once before all tests in the class. If one test modifies the balance (e.g., the payment test deducts $10, leaving $89.70), subsequent tests expecting $100.00 would fail.

`@BeforeEach` runs before every individual test, resetting state to a known baseline. This makes tests **independent** — they can run in any order without affecting each other.

---

## Q7: What does `@ParameterizedTest` with `@CsvSource` add to test quality?

**A:** Instead of writing one `@Test` method per boundary case:

```java
// Without parameterized: 8 separate test methods
@Test void lowTierMin()   { ... }
@Test void lowTierMax()   { ... }
@Test void midTierMin()   { ... }
// ...
```

`@ParameterizedTest` + `@CsvSource` expresses all cases in a data table:
```java
@ParameterizedTest
@CsvSource({ "0.01,1", "9.99,1", "10.00,3", "49.99,3", "50.00,5" })
void getMatchPercentage(String amount, int expectedPct) { ... }
```

**Advantages:**
- Much less code duplication.
- Adding a new test case is one CSV line, not a full new method.
- The test name is generated from the parameters, making failure messages self-explanatory: `amount=10.00 → pct=3 FAILED`.

---

## Q8: What does `@DisplayName` contribute?

**A:** `@DisplayName` replaces the camelCase method name with a readable English sentence in test reports:

```
process_midTier → "Mid-tier match: $10 on $100 balance → newBalance=$89.70, matchPct=3"
```

This is especially useful when test reports are read by non-engineers (PMs, QA, stakeholders) or when CI failures need to be quickly understood without reading source code.

---

## Q9: What are the advantages and disadvantages of this testing approach?

**Advantages:**
| Advantage | Detail |
|---|---|
| No Docker required for tests | Embedded Redis → `mvn verify` works on any machine |
| Fast unit tests | Domain tests have zero I/O; they run in < 100ms total |
| Deterministic dates | Fixed `Clock` → no flaky date-dependent tests |
| Independent tests | `@BeforeEach` reset → tests can run in any order |
| Full-stack coverage | Integration tests verify the entire request → Redis → response path |

**Disadvantages:**
| Disadvantage | Detail |
|---|---|
| Embedded Redis quirks | `embedded-redis` runs a real Redis process; it can fail on some CI platforms (e.g., Apple Silicon Macs require a special build) |
| No contract tests | There are no consumer-driven contract tests (e.g., Pact) to verify the API against actual consumers |
| No load tests | No performance test ensures the endpoint handles burst traffic |
| Integration test port hardcoded | Port `6381` is hardcoded; if it's in use on a CI machine, the test fails |

---

## Q10: What improvements could be made to the testing strategy?

1. **Testcontainers instead of embedded-redis** — use [Testcontainers](https://testcontainers.com/) to start a real Docker Redis container per test run. This eliminates platform-specific embedded Redis issues and tests against the same Redis version used in production:
   ```java
   @Container
   static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
   ```

2. **Random port for embedded Redis** — use port `0` (OS-assigned) to avoid port conflicts on shared CI machines.

3. **Mutation testing (PIT)** — run [PIT](https://pitest.org/) to verify that the test suite actually *detects* bugs (not just executes code). PITest mutates source code and checks that at least one test fails.

4. **Contract tests (Pact)** — define consumer contracts for the `POST /one-time-payment` endpoint. These ensure the API never silently breaks existing consumers.

5. **Performance/load test** — add a Gatling or k6 test that verifies the endpoint handles N requests/second within an acceptable latency.

6. **ArchUnit test** — add an architecture test:
   ```java
   @Test
   void domain_should_not_depend_on_infra() {
       noClasses().that().resideInPackage("..domain..")
           .should().dependOnClassesThat().resideInPackage("..infra..")
           .check(importedClasses);
   }
   ```
   This provides a compile-time and test-time guarantee that the hexagonal structure is never violated.
