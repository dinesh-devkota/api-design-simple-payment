# Q&A 11 — Spring Boot Core Annotations

> Every Spring Boot annotation used in this project — what it does, why it's used, pros, cons, and alternatives.

---

## `@SpringBootApplication`

### What it does
```java
@SpringBootApplication
public class CustomerCareApplication { ... }
```
A composed meta-annotation equivalent to three annotations combined:
1. `@SpringBootConfiguration` — marks this as a Spring Boot configuration class (specialisation of `@Configuration`).
2. `@EnableAutoConfiguration` — activates Spring Boot's auto-configuration mechanism, which detects classpath libraries and wires appropriate beans (e.g., detects `spring-boot-starter-data-redis` → auto-configures `RedisConnectionFactory`).
3. `@ComponentScan` — scans the current package (`com.customercare`) and all sub-packages for `@Component`, `@Service`, `@Repository`, `@Controller` beans.

### Why here?
Placed in the root package `com.customercare` so the scan covers all four Maven modules' packages: `com.customercare.domain`, `com.customercare.app`, `com.customercare.infra`.

### Pros
- One annotation replaces three.
- Auto-configuration eliminates boilerplate wiring (no manual `RedisConnectionFactory` bean needed).
- Component scan covers all sub-packages automatically.

### Cons
- Too broad a scan can pick up unwanted beans if the package structure is not disciplined.
- Auto-configuration can wire beans you didn't expect; requires `spring-boot-autoconfigure-processor` knowledge to debug.
- Disabling specific auto-configs requires `exclude` attributes: `@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)`.

---

## `@Service`

### What it does
```java
@Service
public class ProcessPaymentService implements ProcessPaymentUseCase { ... }
```
A specialisation of `@Component`. Marks a class as a **service layer bean** — Spring registers it in the application context and injects it wherever its interface type is requested.

Semantically equivalent to `@Component` at runtime, but communicates **intent**: "this is a business-logic service, not a repository or controller."

### Used on
- `ProcessPaymentService`
- `MatchCalculationServiceImpl`
- `DueDateCalculationServiceImpl`

### Pros
- Self-documenting: immediately signals "this is a domain service."
- Enables Spring's exception translation proxy for `@Repository` differentiation.
- Automatically a candidate for `@Autowired` / constructor injection.

### Cons
- No functional difference from `@Component` — the distinction is purely semantic/conventional.
- Putting `@Service` on domain classes creates a **soft Spring dependency** in the domain layer (imports `org.springframework.stereotype.Service`). Purists argue the domain should be framework-free and suggest the `@Service` annotation should only be on adapter classes. The domain could rely on plain interfaces and let `bootstrap` register beans via `@Bean` factory methods instead.

---

## `@Component`

### What it does
```java
@Component
public class AccountAdapter implements AccountSpi { ... }

@Component
public class IdempotencyGuard { ... }

@Component
public class RedisIdempotencyStore implements IdempotencyStoreSpi { ... }
```
The base stereotype annotation. Marks a class as a Spring-managed component — Spring creates one instance (by default, singleton-scoped) and registers it in the application context.

### Pros
- Generic — appropriate for classes that don't fit `@Service`, `@Repository`, or `@Controller` semantics.
- Spring handles lifecycle, injection, and AOP proxying.

### Cons
- Less expressive than specialised annotations — a reader has to look at the class to understand its role.
- Singleton scope is implicit — if a class should be prototype-scoped (new instance per injection point), you must add `@Scope("prototype")` explicitly.

---

## `@Repository`

### What it does
```java
@Repository
public interface AccountRedisRepository extends CrudRepository<AccountEntity, String> { }
```
Specialisation of `@Component` for persistence layer beans. Additionally:
1. Marks the class as a persistence component (tooling and documentation benefit).
2. Enables Spring's **persistence exception translation** — wraps store-specific exceptions (e.g., `DataAccessException`) into Spring's unified exception hierarchy.

### Pros
- Spring Data will generate a full CRUD implementation at startup (no implementation class needed for standard operations).
- Exception translation unifies different data stores' exception types.

### Cons
- Spring Data's generated implementation hides the actual Redis operations, which can make debugging harder.
- `@Repository` on a Spring Data interface is technically redundant (Spring Data registers it automatically), but it's a helpful readability hint.

---

## `@Configuration` and `@Bean`

### What they do
```java
@Configuration
public class RedisConfig {
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory cf) { ... }
}

@Configuration
public class ClockConfig {
    @Bean
    @ConditionalOnProperty(name = "app.fixed-date")
    public Clock fixedClock(@Value("${app.fixed-date}") String fixedDate) { ... }

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock systemClock() { return Clock.systemDefaultZone(); }
}
```
- `@Configuration` — marks the class as a source of `@Bean` definitions. Spring processes it as a CGLIB proxy so that `@Bean` methods called from within the class return the same singleton instance (not a new object each time).
- `@Bean` — marks a method as a factory for a Spring bean. The return value is registered in the context under the method name (or an alias).

### Why use `@Bean` instead of `@Component` + `@Service`?
`@Bean` is used when you need to configure a bean that you don't own (e.g., `RedisTemplate`, `ObjectMapper`, `Clock`) — you can't add `@Component` to library classes.

### Pros
- Full control over instantiation, configuration, and conditional activation.
- Works with any class, including third-party library classes.
- Configuration code is explicit and traceable.

### Cons
- `@Configuration` classes are CGLIB-proxied, which adds a small startup overhead.
- `@Bean` methods can be called as regular Java methods from tests (when not proxied), leading to confusion if instantiation side effects are expected.

---

## `@ConditionalOnProperty` and `@ConditionalOnMissingBean`

### What they do
```java
@Bean
@ConditionalOnProperty(name = "app.fixed-date")  // only if property is set
public Clock fixedClock(...) { ... }

@Bean
@ConditionalOnMissingBean(Clock.class)            // only if no Clock bean exists yet
public Clock systemClock() { ... }
```

`@ConditionalOnProperty` — the bean is registered only when the specified property is present (and optionally has a specific value). Used here to activate the fixed clock only in development/demo environments.

`@ConditionalOnMissingBean` — the bean is registered only when no bean of the specified type already exists. This is the **fallback pattern**: if `fixedClock` was registered (because the property is set), `systemClock` is skipped. Otherwise, `systemClock` is the default.

### Pros
- Clean environment-specific bean wiring without `@Profile` boilerplate.
- Enables library-style extensibility — a library can provide a default bean that consumers override.

### Cons
- Condition evaluation order matters; misuse can cause both beans to register or neither to register.
- Debugging unexpected bean activation requires understanding auto-configuration report (`--debug` flag).
- `@ConditionalOnProperty` silently does nothing if the property name is misspelled.

---

## `@Value`

### What it does
```java
public Clock fixedClock(@Value("${app.fixed-date}") String fixedDate) { ... }
```
Injects an application property value directly into a method parameter or field. Spring evaluates the SpEL expression `${app.fixed-date}` against the `Environment` (which merges all YAML/properties files and system properties).

### Pros
- Concise property injection.
- Supports SpEL default values: `@Value("${app.timeout:30}")` → 30 if unset.

### Cons
- No compile-time validation — a typo in the property key silently injects the literal string (or throws `IllegalArgumentException` at startup).
- Less refactor-safe than `@ConfigurationProperties` (a typed POJO bound to a prefix).
- Cannot be used in non-Spring classes (must be a Spring-managed bean).

### Improvement
For multiple related properties, prefer `@ConfigurationProperties`:
```java
@ConfigurationProperties(prefix = "app")
public record AppProperties(String fixedDate, int dueDateOffsetDays) {}
```

---

## `@RestController`

### What it does
```java
@RestController
public class PaymentController implements PaymentApi { ... }
```
A composed annotation: `@Controller` + `@ResponseBody`. It:
1. Registers the class as a Spring MVC controller (a specialised `@Component`).
2. Makes every handler method's return value serialized directly to the HTTP response body (no view resolution).

### Pros
- Eliminates the need for `@ResponseBody` on every method.
- Signals clearly: "this is a REST controller, not a page-rendering controller."

### Cons
- Puts serialization concerns on the return type — if you need to return a view for one method and JSON for another, `@Controller` with per-method `@ResponseBody` is needed.

---

## `@RestControllerAdvice`

### What it does
```java
@RestControllerAdvice
public class GlobalExceptionHandler { ... }
```
Applies globally to all `@RestController` classes. See Q&A 09 for full details. Equivalent to `@ControllerAdvice` + `@ResponseBody`.

---

## `@ExceptionHandler`

### What it does
```java
@ExceptionHandler(AccountNotFoundException.class)
public ResponseEntity<ErrorResponse> handleAccountNotFound(AccountNotFoundException ex) { ... }
```
Maps a specific exception type to a handler method. When any `@RestController` throws `AccountNotFoundException`, Spring invokes this method instead of propagating the exception.

### Priority
More specific exception types take priority over more general ones. `AccountNotFoundException extends RuntimeException`, so its handler wins over `@ExceptionHandler(Exception.class)`.

---

## `@RequestBody` and `@Valid`

### What they do
```java
public ResponseEntity<OneTimePaymentResponse> oneTimePayment(
        @Valid @RequestBody OneTimePaymentRequest request, ...) { ... }
```
- `@RequestBody` — deserializes the HTTP request body JSON into a Java object using Jackson.
- `@Valid` — triggers Bean Validation (JSR-380) on the deserialized object. If any constraint fails (e.g., `paymentAmount < 0.01`), Spring throws `MethodArgumentNotValidException` before the method body executes.

### Pros of `@Valid`
- Zero-boilerplate input validation — constraints are declared in the OpenAPI spec, generated into the DTO, and enforced automatically.
- Errors are collected across all fields and returned as a list (not just the first failure).

### Cons
- Validation happens at the HTTP layer, not the domain layer — the domain must still validate for defence-in-depth (and does, in `ProcessPaymentService`).
- Complex cross-field validations (e.g., "field A required if field B is set") need a custom `@Constraint` validator.

---

## `@Autowired` (test usage)

### What it does
```java
@Autowired
private TestRestTemplate restTemplate;

@Autowired
private AccountRedisRepository accountRedisRepository;
```
Injects beans from the test Spring context into test fields. Used only in integration tests where a Spring context is running.

### Best practice
Constructor injection is preferred over field injection in production code (see Q&A 13 on Lombok). In test classes, field `@Autowired` is acceptable because tests are never injected into other classes.

---

## Summary Table

| Annotation | Module | Purpose | Alternative |
|---|---|---|---|
| `@SpringBootApplication` | bootstrap | Entry point + auto-config + scan | Split into 3 separate annotations |
| `@Service` | domain | Business-logic bean | `@Component` (semantically weaker) |
| `@Component` | app, infra | General-purpose bean | `@Bean` factory method in `@Configuration` |
| `@Repository` | infra | Persistence bean + exception translation | `@Component` (loses exception translation) |
| `@Configuration` | infra, bootstrap | Bean factory class | `@Component` (less clear, no CGLIB proxy) |
| `@Bean` | infra, bootstrap | Factory method for external/library beans | `@Component` (can't use on library classes) |
| `@ConditionalOnProperty` | bootstrap | Activate bean based on property | `@Profile` |
| `@ConditionalOnMissingBean` | bootstrap | Default/fallback bean | Manual null check |
| `@Value` | bootstrap | Inject single property | `@ConfigurationProperties` (typed) |
| `@RestController` | app | REST endpoint class | `@Controller` + `@ResponseBody` |
| `@RestControllerAdvice` | app | Global exception handler | `@ControllerAdvice` + `@ResponseBody` |
| `@ExceptionHandler` | app | Map exception → HTTP response | try/catch in every controller (bad) |
| `@RequestBody` | app (generated) | Deserialize HTTP body | Manual `HttpServletRequest` read |
| `@Valid` | app | Trigger Bean Validation | Manual validation code |
