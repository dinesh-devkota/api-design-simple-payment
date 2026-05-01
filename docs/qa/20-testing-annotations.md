# Q&A 20 — Spring Boot Testing Annotations: Every Annotation Explained

> `@SpringBootTest`, `@WebMvcTest`, `@ExtendWith`, `@Mock`, `@BeforeEach`, `@ParameterizedTest`, `@CsvSource`, `@DisplayName`, `@Autowired`, `@ContextConfiguration`, `@AfterAll` — what each does, when to use it, and what goes wrong.

---

## `@SpringBootTest`

### What it does
```java
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
```
Loads the **full Spring application context** — all beans, all auto-configuration, all `@Configuration` classes. `webEnvironment = RANDOM_PORT` starts an actual embedded HTTP server on a random available port, allowing real HTTP calls via `TestRestTemplate`.

### Options for `webEnvironment`
| Value | Effect |
|---|---|
| `RANDOM_PORT` | Starts embedded server on a random port. Use with `TestRestTemplate` for full HTTP tests |
| `DEFINED_PORT` | Starts on port from config (8080). Risks port conflicts in CI |
| `MOCK` (default) | No real server; uses `MockMvc`. Faster but doesn't test HTTP stack |
| `NONE` | No web environment at all. For testing non-web Spring services |

### When to use
Only for true end-to-end integration tests. It is the **slowest** test type — starts the full Spring context (Redis auto-config, all beans, the web server). Use `@WebMvcTest` or plain unit tests for everything else.

### Pitfall
Without overriding `spring.data.redis.port`, `@SpringBootTest` tries to connect to Redis on the default port 6379. If Redis is not running (e.g., on a CI machine with no Docker), the context fails to start. The `RedisTestInitializer` in this project handles this by starting embedded Redis before the context initialises.

---

## `@ContextConfiguration(initializers = ...)`

### What it does
```java
@ContextConfiguration(initializers = PaymentControllerIntegrationTest.RedisTestInitializer.class)
```
Registers one or more `ApplicationContextInitializer` implementations that run **before** the Spring context is created. Used here to:
1. Start embedded Redis on port 6381.
2. Override `spring.data.redis.port` to 6381 using `TestPropertySourceUtils.addInlinedPropertiesToEnvironment()`.

### Why before context creation matters
Spring Boot's `RedisConnectionFactory` auto-configuration runs at context startup. It tries to connect to Redis using the configured port. If embedded Redis starts *after* the context, the connection fails. The initializer guarantees Redis is ready before Spring tries to connect.

### Alternative
`@DynamicPropertySource` (Spring 5.2.5+) is cleaner:
```java
@DynamicPropertySource
static void redisProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.port", () -> redisServer.getBindPort());
}
```
It sets properties dynamically after the container starts but before Spring reads them. However, with `embedded-redis`, the server must still be started before the context — the initializer approach is safer here.

---

## `@WebMvcTest`

### What it does
```java
@WebMvcTest(HelloController.class)
```
Loads only the **Spring MVC slice**: controllers, `@ControllerAdvice`, `Filter`, `WebMvcConfigurer`. Does NOT load `@Service`, `@Repository`, `@Component` beans or any infrastructure auto-configuration (Redis, datasource).

Provides an auto-configured `MockMvc` bean for simulating HTTP requests without a real server.

### When to use
Controller-layer tests where you want to verify routing, request/response serialization, validation, and exception handling — without any business logic or persistence. Use `@MockBean` to stub service dependencies.

### Why it's faster than `@SpringBootTest`
No application context startup overhead for infrastructure. No Redis connection attempted.

### Pitfall
`@WebMvcTest` will fail if the controller has a `@Service` dependency that is not `@MockBean`ed:
```
No qualifying bean of type 'ProcessPaymentUseCase' available
```
Always stub injected service dependencies with `@MockBean`.

---

## `@ExtendWith(MockitoExtension.class)`

### What it does
```java
@ExtendWith(MockitoExtension.class)
class ProcessPaymentServiceTest { ... }
```
Registers Mockito's JUnit 5 extension, which:
1. Processes `@Mock` annotations — creates mock objects before each test.
2. Processes `@InjectMocks` annotations — injects created mocks into the target object.
3. Validates mock usage after each test — detects unnecessary stubbing (`UnnecessaryStubbingException`).

**No Spring context is started.** This is the fastest kind of test.

### Difference from `@ExtendWith(SpringExtension.class)`
`SpringExtension` starts a Spring context. `MockitoExtension` uses pure Mockito — no Spring involved.

---

## `@Mock`

### What it does
```java
@Mock
private AccountSpi accountSpi;
```
Creates a **Mockito mock** — a dynamic proxy implementing `AccountSpi` that records all method calls and returns default values (`null`, `0`, `false`, `Optional.empty()`) unless stubbed with `when(...).thenReturn(...)`.

### vs `@MockBean`
| Annotation | Context | What it does |
|---|---|---|
| `@Mock` | No Spring context (plain Mockito test) | Creates a Mockito mock, manually injected |
| `@MockBean` | Spring context test (`@SpringBootTest`, `@WebMvcTest`) | Creates a Mockito mock AND registers it as a Spring bean, replacing the real bean |

---

## `@BeforeEach`

### What it does
```java
@BeforeEach
void setUp() {
    accountRedisRepository.deleteAll();
    accountRedisRepository.save(new AccountEntity("user-001", ...));
}
```
Runs before **every single test method** in the class. Ensures a clean, known state before each test runs.

### vs `@BeforeAll`
| Annotation | Runs | Method must be |
|---|---|---|
| `@BeforeEach` | Before every test method | Instance method |
| `@BeforeAll` | Once before all test methods in the class | `static` method |

`@BeforeEach` for state reset — tests are isolated.  
`@BeforeAll` for expensive setup that doesn't need resetting (e.g., starting a container, creating a read-only test fixture).

---

## `@AfterAll`

### What it does
```java
@AfterAll
static void stopRedis() {
    redisServer.stop();
}
```
Runs once after all test methods in the class complete (or fail). Used here to stop the embedded Redis process, freeing the port and preventing resource leaks.

### Must be `static`
`@AfterAll` (and `@BeforeAll`) must be `static` because they run when no test instance exists. JUnit 5 creates a new instance per test method by default; `@AfterAll` runs after all instances are gone, so it must not depend on instance state.

---

## `@Test`

### What it does
Marks a method as a test case. JUnit 5 discovers and executes it. If the method throws any exception, the test fails. If it returns normally, the test passes.

No `public` modifier required in JUnit 5 (unlike JUnit 4). Package-private or even private test methods work.

---

## `@ParameterizedTest` and `@CsvSource`

### What they do
```java
@ParameterizedTest(name = "amount={0} → pct={1}")
@CsvSource({
    "0.01, 1",
    "10.00, 3",
    "50.00, 5"
})
void getMatchPercentage(String amount, int expectedPct) { ... }
```

`@ParameterizedTest` runs the test method once for each set of arguments. JUnit 5 uses the `name` template to generate a display name for each invocation.

`@CsvSource` provides argument sets as comma-separated strings. Each string becomes one test invocation. Arguments are auto-converted to the method parameter types (`String` → `BigDecimal` via `BigDecimalArgumentConverter`; `String` → `int` via implicit conversion).

### Other sources
| Source | Use case |
|---|---|
| `@CsvSource` | Inline CSV literals in source code |
| `@CsvFileSource` | CSV data in a file under `src/test/resources` |
| `@MethodSource` | Java method returning a `Stream` of arguments |
| `@ValueSource` | Single parameter, multiple values |
| `@EnumSource` | All or selected values of an enum |

---

## `@DisplayName`

### What it does
```java
@DisplayName("Mid-tier match: $10 on $100 balance → newBalance=$89.70, matchPct=3")
```
Replaces the method name in test reports with a human-readable description. The method name (`process_midTier`) is for navigation; the `@DisplayName` is for communication.

Without it, a failing test shows: `process_midTier FAILED`.  
With it: `Mid-tier match: $10 on $100 balance → newBalance=$89.70, matchPct=3 FAILED`.

Particularly valuable when test reports are read by non-engineers or in CI pipelines where only the failure message is visible.

---

## `@Autowired` (in tests)

### What it does
Injects a Spring-managed bean into a test field. Used in integration tests where a Spring context is running:
```java
@Autowired
private TestRestTemplate restTemplate;

@Autowired
private AccountRedisRepository accountRedisRepository;
```

### Why field injection is acceptable in tests
In production code, constructor injection is strongly preferred (see Q&A 13 on `@RequiredArgsConstructor`). In test classes, field injection is acceptable because:
- Test classes are never injected into other classes.
- The `@Autowired` fields are set by JUnit/Spring before tests run.
- Constructor injection in tests would require writing boilerplate constructors just for the test class.

---

## `@SpringBootTest` vs `@WebMvcTest` vs plain unit test — decision tree

```
Does the test require real HTTP calls and a real server?
  YES → @SpringBootTest(webEnvironment = RANDOM_PORT) + TestRestTemplate

Does the test require the Spring MVC layer (routing, serialization, validation)?
  YES → @WebMvcTest + MockMvc + @MockBean for services

Does the test only need to verify business logic (no Spring, no HTTP)?
  YES → Plain JUnit 5 + @ExtendWith(MockitoExtension.class) + @Mock

Does the test need multiple input combinations?
  YES → @ParameterizedTest + @CsvSource
```

---

## Summary Table

| Annotation | From | Requires Spring? | Speed | Use for |
|---|---|---|---|---|
| `@SpringBootTest` | Spring Boot Test | Full context | Slow | Full stack integration tests |
| `@WebMvcTest` | Spring Boot Test | MVC slice | Medium | Controller layer tests |
| `@ExtendWith(MockitoExtension)` | JUnit 5 + Mockito | No | Fast | Unit tests with mocks |
| `@Mock` | Mockito | No | — | Create mock dependencies |
| `@MockBean` | Spring Boot Test | Yes | — | Replace Spring bean with mock |
| `@Autowired` | Spring | Yes | — | Inject Spring beans in tests |
| `@BeforeEach` | JUnit 5 | No | — | Reset state before each test |
| `@BeforeAll` | JUnit 5 | No | — | One-time setup before all tests |
| `@AfterAll` | JUnit 5 | No | — | One-time teardown after all tests |
| `@Test` | JUnit 5 | No | — | Mark a method as a test |
| `@ParameterizedTest` | JUnit 5 | No | — | Run test with multiple inputs |
| `@CsvSource` | JUnit 5 | No | — | Provide inline CSV arguments |
| `@DisplayName` | JUnit 5 | No | — | Human-readable test name |
| `@ContextConfiguration` | Spring Test | Yes | — | Register custom context initializers |
