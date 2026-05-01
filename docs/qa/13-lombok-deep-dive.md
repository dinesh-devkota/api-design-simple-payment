# Q&A 13 — Lombok: Every Annotation Used, How It Works, Pros, Cons, Pitfalls

> A deep-dive into every Lombok annotation in this codebase, how they generate code at compile time, and when they cause problems.

---

## Q1: What is Lombok and how does it work mechanically?

**A:** Lombok is a Java annotation processor — it runs during `javac`'s annotation processing phase (before bytecode generation) and **modifies the AST (Abstract Syntax Tree)** of your source files to inject methods like `getters`, `setters`, `constructors`, `equals`, `hashCode`, and `toString` directly into the compiled `.class` file.

It does **not** generate separate `.java` source files (unlike MapStruct). It operates at a lower level, hooking into the compiler's internal API (`com.sun.tools.javac`), which is why:
- You don't see generated source files in `target/`.
- Your IDE needs a Lombok plugin to understand the injected methods.
- Lombok is configured as an `annotationProcessorPath` in `pom.xml`, not a regular `<dependency>`.

```xml
<annotationProcessorPaths>
    <path>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
    </path>
    <path>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok-mapstruct-binding</artifactId>  <!-- ensures Lombok runs BEFORE MapStruct -->
        <version>0.2.0</version>
    </path>
</annotationProcessorPaths>
```

The `lombok-mapstruct-binding` dependency is critical: it ensures Lombok generates getters/setters **before** MapStruct reads them to generate mapper code. Without it, MapStruct sees a class with no getters and generates an empty mapper.

---

## Q2: What does `@Data` do and what are its hidden dangers?

**A:** `@Data` is a shortcut that combines:
- `@Getter` — generates a getter for every field.
- `@Setter` — generates a setter for every non-`final` field.
- `@ToString` — generates `toString()` including all fields.
- `@EqualsAndHashCode` — generates `equals()` and `hashCode()` based on all non-`static`, non-`transient` fields.
- `@RequiredArgsConstructor` — generates a constructor for `final` and `@NonNull` fields.

Used on `Account` and `AccountEntity`.

### Hidden dangers of `@Data`

**1. Mutable objects in collections break if used as Map keys or Set members.**
`@Data` generates `hashCode()` from all fields. If an `Account` object is placed in a `HashSet` and its `balance` field is later changed via `setBalance()`, the object's hash code changes and the `HashSet` can no longer find it. The object is effectively "lost" in the collection.

**2. `@EqualsAndHashCode` on JPA entities causes the N+1 problem.**
(Not applicable here since `AccountEntity` uses Redis, not JPA, but important to know.) JPA lazy-loaded collections accessed inside `equals()`/`hashCode()` trigger unintended DB queries.

**3. `@ToString` causes infinite recursion with bidirectional relationships.**
If `Account` had a `List<Payment>` and `Payment` had a back-reference to `Account`, `toString()` would recurse infinitely. Use `@ToString(exclude = "payments")` to break the cycle.

**4. `@Data` on domain entities violates encapsulation.**
Generating public setters for every field makes the object mutable from anywhere. A `balance` that should only be changed through a domain method can be changed by anyone holding a reference.

---

## Q3: What does `@Builder` do and when should it be used?

**A:** `@Builder` generates a static inner `Builder` class with a fluent API:

```java
Account account = Account.builder()
    .userId("user-001")
    .balance(new BigDecimal("100.00"))
    .build();
```

The generated `Builder` has a method for every field, a `build()` method that calls the all-args constructor, and a static `builder()` factory method on the class.

### Used on: `Account`, `AccountEntity`

### Why useful
- Makes object construction readable when there are many fields — no positional argument confusion.
- Forces explicit field naming at construction sites.
- `build()` is a natural place to add validation (though Lombok's default `build()` does not validate).

### Pitfalls

**1. `@Builder` without `@AllArgsConstructor` breaks Jackson deserialization.**
Jackson needs a no-args constructor or a `@JsonDeserialize(builder = ...)` annotation to deserialize JSON. `@Builder` alone doesn't provide either. Fix: add `@NoArgsConstructor` + `@AllArgsConstructor`, or annotate the builder class with `@JsonPOJOBuilder`.

**2. `@Builder` + `@Data` together can conflict.**
`@Data` generates `@RequiredArgsConstructor`. `@Builder` generates `@AllArgsConstructor`. If you add `@NoArgsConstructor` too (as in `Account`), you must also explicitly add `@AllArgsConstructor` — otherwise `@Builder` will try to call a constructor that doesn't exist. This is why `Account` has all four Lombok annotations.

**3. Builder does not enforce required fields at compile time.**
Nothing stops you from calling `Account.builder().build()` without setting `userId` or `balance`. You get a `null`-filled object with no error.

---

## Q4: What does `@NoArgsConstructor` and `@AllArgsConstructor` do?

**A:**
- `@NoArgsConstructor` — generates `public Account() {}`. Required by Jackson for JSON deserialization and by JPA/Hibernate for entity instantiation.
- `@AllArgsConstructor` — generates `public Account(String userId, BigDecimal balance) {}`. Required by `@Builder` when `@NoArgsConstructor` is also present (otherwise the builder can't call a constructor with all fields).

### Why both are needed alongside `@Builder`
Without `@NoArgsConstructor`, Jackson cannot deserialize JSON into `Account`. Without `@AllArgsConstructor`, `@Builder`'s generated `build()` method has no all-args constructor to call.

---

## Q5: What does `@RequiredArgsConstructor` do and why is it used for Spring injection?

**A:** Generates a constructor for every field that is `final` or annotated `@NonNull`:

```java
@RequiredArgsConstructor
public class ProcessPaymentService implements ProcessPaymentUseCase {
    private final AccountSpi              accountSpi;
    private final MatchCalculationService matchCalculationService;
    private final DueDateCalculationService dueDateCalculationService;
    private final Clock                   clock;
    // Generated:
    // public ProcessPaymentService(AccountSpi accountSpi, MatchCalculationService matchCalc, ...) { ... }
}
```

Spring's dependency injection detects this single constructor and uses it automatically — no `@Autowired` annotation needed on the constructor (Spring 4.3+).

### Why constructor injection over field injection?

| Constructor injection (`@RequiredArgsConstructor`) | Field injection (`@Autowired` on field) |
|---|---|
| Dependencies are `final` — immutable after construction | Fields can be null if the Spring context is not running |
| Works in unit tests without Spring — pass mocks directly | Requires Spring context or reflection to inject in tests |
| Fails fast at startup if a dependency is missing | Fails at call-time with `NullPointerException` |
| Explicit contract — callers know what the class needs | Hidden contract — dependencies invisible in constructor |

This is why `@RequiredArgsConstructor` + `private final` is the standard pattern for Spring services in modern Java.

---

## Q6: What does `@Slf4j` do?

**A:** Generates a `private static final Logger log = LoggerFactory.getLogger(ClassName.class)` field at the top of the class.

Without Lombok:
```java
private static final Logger log = LoggerFactory.getLogger(ProcessPaymentService.class);
```
With Lombok:
```java
@Slf4j  // that's it
```

### Used on: `ProcessPaymentService`, `AccountAdapter`, `RedisIdempotencyStore`, `PaymentController`, `HelloController`, `GlobalExceptionHandler`, `ClockConfig`, `IdempotencyGuard`

### Variants
- `@Slf4j` — SLF4J (used here, works with Logback, Log4j2, or any SLF4J binding).
- `@Log4j2` — Log4j2 directly.
- `@CommonsLog` — Apache Commons Logging.

### Pitfall
The generated `log` field uses the class where `@Slf4j` is declared as the logger name. If you copy-paste a class and forget to update it, the logger name is correct automatically — unlike hand-written `LoggerFactory.getLogger(WrongClass.class)` which silently logs under the wrong category.

---

## Q7: Why is `@Data` used on `Account` but a Java `record` used for `PaymentResult`?

**A:** Design intent:

| `Account` (`@Data`) | `PaymentResult` (record) |
|---|---|
| Needs to be **mutable** — `setBalance()` is called by the use-case | Should be **immutable** — a snapshot of the outcome, never changed |
| Needs `@Builder` for ergonomic test construction | Record's compact constructor serves the same purpose |
| Needs `@NoArgsConstructor` for Jackson/Spring Data | Records generate a canonical constructor automatically |

An improvement would be to make `Account` immutable too — return a new instance with the updated balance instead of mutating the existing one. This would allow `Account` to be a record as well, eliminating the entire Lombok dependency in the domain model.

---

## Q8: What are the overall advantages and disadvantages of Lombok?

### Advantages
| Advantage | Detail |
|---|---|
| Eliminates boilerplate | No hand-written getters, setters, constructors, `toString` |
| Reduces merge conflicts | Less generated code means fewer lines to conflict on |
| Keeps classes readable | The important parts (fields and business methods) are not buried in accessors |
| Constructor injection without ceremony | `@RequiredArgsConstructor` + `final` is the cleanest Spring injection pattern |

### Disadvantages
| Disadvantage | Detail |
|---|---|
| IDE plugin required | Without the Lombok IntelliJ/Eclipse plugin, the IDE shows compile errors |
| Hidden code | Bugs in generated `equals`/`hashCode` are hard to spot without delomboking |
| Annotation interaction complexity | `@Builder` + `@Data` + `@NoArgsConstructor` ordering matters and causes subtle bugs |
| Framework coupling risk | `@Data` on domain objects leaks mutability; records are a better Java-native alternative |
| Delombok is a last resort | If Lombok must be removed, `mvn lombok:delombok` rewrites source files — but the resulting code is ugly |

---

## Q9: What is `delombok` and when would you use it?

**A:** `delombok` is a Lombok tool that expands all Lombok annotations into explicit Java code in-place:

```bash
mvn lombok:delombok
```

You would use it if:
- You need to migrate off Lombok (e.g., the project moves to a framework incompatible with annotation processing).
- You need to publish a library and don't want to force consumers to have Lombok on their classpath.
- You need to generate Javadoc from a Lombok-annotated class (Lombok annotations don't produce Javadoc for generated methods; delombok does).

In normal development, never use it — it makes the codebase harder to maintain.
