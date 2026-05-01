# Q&A 26 — MapStruct Deep Dive

> Every aspect of MapStruct used in this project — why code generation, how it works, edge cases, and interview traps.

---

## Q1: What is MapStruct and why use it instead of manual mapping?

**A:** MapStruct is an annotation processor that generates type-safe Java mapping code at compile time. Given a mapper interface:

```java
@Mapper(componentModel = "spring")
public interface PaymentResponseMapper {
    OneTimePaymentResponse toResponse(PaymentResult result);
}
```

MapStruct generates a concrete `PaymentResponseMapperImpl` class at compile time. At runtime, there is no reflection — just generated setter/getter calls.

**vs manual mapping:**
- Manual: `response.setPaymentAmount(result.paymentAmount())` — tedious, error-prone, no compile-time safety if field names change.
- MapStruct: compile-time error if source and target fields cannot be matched — catches regressions immediately.
- ModelMapper / Dozer: runtime reflection — slower, no compile-time safety, harder to debug.

---

## Q2: Why does MapStruct need `lombok-mapstruct-binding` and why does order matter in `annotationProcessorPaths`?

**A:** Both Lombok and MapStruct are annotation processors. The problem:

1. MapStruct reads the compiled class structure to generate mappers.
2. Lombok generates the getters/setters/constructors that MapStruct needs to see.
3. If MapStruct runs first, it sees the original class without Lombok-generated methods — it cannot find the fields to map — it generates broken or empty mapper code.

`lombok-mapstruct-binding` is a shim that forces Lombok to complete processing before MapStruct starts.

**In `pom.xml`:**
```xml
<annotationProcessorPaths>
    <path>org.projectlombok:lombok:...</path>                  <!-- 1st: generates getters/setters -->
    <path>org.projectlombok:lombok-mapstruct-binding:...</path> <!-- 2nd: ordering glue -->
    <path>org.mapstruct:mapstruct-processor:...</path>          <!-- 3rd: reads Lombok output -->
</annotationProcessorPaths>
```

If you reverse Lombok and MapStruct, the build compiles but the generated mapper throws `NullPointerException` or `NoSuchMethodError` at runtime.

---

## Q3: What does `componentModel = "spring"` do?

**A:** Without it, MapStruct generates:

```java
public class PaymentResponseMapperImpl implements PaymentResponseMapper {
    public static final PaymentResponseMapper INSTANCE = new PaymentResponseMapperImpl();
}
```

A static singleton — not a Spring bean. You'd call `PaymentResponseMapper.INSTANCE.toResponse(...)`.

With `componentModel = "spring"`:

```java
@Component
public class PaymentResponseMapperImpl implements PaymentResponseMapper { ... }
```

It becomes a Spring-managed bean, injectable via `@Autowired` or constructor injection. The controller can declare `PaymentResponseMapper mapper` as a constructor parameter and Spring wires it.

---

## Q4: How does MapStruct handle `LocalDate` → `String` conversion?

**A:** MapStruct generates straight getter/setter calls. If source is `LocalDate paymentDueDate` and target is `LocalDate paymentDueDate` (same type), it generates:
```java
target.setPaymentDueDate(source.paymentDueDate());  // record accessor
```

If source is `LocalDate` and target is `String`, MapStruct does NOT automatically convert — it reports an "unmapped property" warning or error (depending on `unmappedTargetPolicy`). You must add an explicit `@Mapping`:

```java
@Mapping(target = "paymentDueDate", dateFormat = "yyyy-MM-dd")
OneTimePaymentResponse toResponse(PaymentResult result);
```

In this project, both `PaymentResult` and `OneTimePaymentResponse` use `LocalDate` — Jackson handles the String serialization when writing the HTTP response.

---

## Q5: What is `@Mapping(ignore = true)` and when do you need it?

**A:** If `OneTimePaymentResponse` has a field that does not exist in `PaymentResult`, MapStruct will warn (or error) about unmapped target properties. `ignore = true` explicitly tells MapStruct to leave that field as its default value.

```java
@Mapping(target = "internalAuditId", ignore = true)  // not in domain, added only in response
OneTimePaymentResponse toResponse(PaymentResult result);
```

The alternative is `@BeanMapping(ignoreByDefault = true)` which only maps explicitly listed fields — safer for large objects with many fields.

---

## Q6: What is the risk of MapStruct silently mapping wrong fields?

**A:** MapStruct matches source and target by **name**. If you rename a domain field but forget to update the mapper:

```java
// PaymentResult before: BigDecimal paymentAmount
// PaymentResult after: BigDecimal amount  (renamed)
// OneTimePaymentResponse still expects: BigDecimal paymentAmount
```

MapStruct sees `amount` on source and `paymentAmount` on target — different names — **not mapped** — target field is `null`. No compile error by default.

**Fix:** Use `@BeanMapping(unmappedTargetPolicy = ReportingPolicy.ERROR)` or configure it globally in `MapperConfig`:
```java
@MapperConfig(unmappedTargetPolicy = ReportingPolicy.ERROR)
```

This makes any unmapped target field a **compile-time error** — impossible to miss during the build.

---

## Q7: Can MapStruct map between types in different Maven modules?

**A:** Yes — MapStruct only cares about the Java types on the classpath at compile time. `PaymentResult` is in `domain`, `OneTimePaymentResponse` is generated by the OpenAPI plugin and lives in `app`. `PaymentResponseMapper` is in `app`.

The `app` module's `pom.xml` declares dependencies on both `domain` (for `PaymentResult`) and the generated DTOs (in its own compile scope). MapStruct sees both types and generates the mapper.

**Requirement:** All types must be on the same annotation-processing classpath. Types in `provided` or `test` scope are accessible. Types in a separate Maven module must be listed as a `<dependency>` (not just `<annotationProcessorPaths>`).

---

## Q8: How do you test a MapStruct mapper?

**A:** Two approaches:

**Unit test (no Spring):**
```java
@Test
void mapsPaymentResultToResponse() {
    PaymentResponseMapper mapper = Mappers.getMapper(PaymentResponseMapper.class);
    PaymentResult result = new PaymentResult(new BigDecimal("500.00"), new BigDecimal("50.00"),
        new BigDecimal("450.00"), LocalDate.of(2026, 4, 28));
    
    OneTimePaymentResponse response = mapper.toResponse(result);
    
    assertThat(response.getPaymentAmount()).isEqualByComparingTo(new BigDecimal("500.00"));
    assertThat(response.getPaymentDueDate()).isEqualTo(LocalDate.of(2026, 4, 28));
}
```

`Mappers.getMapper()` uses the generated `Impl` class without Spring — fast, no application context.

**Spring context test:**
```java
@ExtendWith(SpringExtension.class)
@Import(PaymentResponseMapperImpl.class)
class PaymentResponseMapperTest { ... }
```

The Spring approach is only needed if the mapper has injected dependencies (e.g., custom `@Qualifier` converters).

---

## Q9: What is a `@Qualifier` in MapStruct (not Spring)?

**A:** MapStruct has its own `@Qualifier` annotation (`org.mapstruct.Qualifier`). It selects between multiple methods that could convert the same source type.

```java
@Qualifier
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface MoneyToString {}

@MoneyToString
public String bigDecimalToString(BigDecimal value) {
    return value.setScale(2, HALF_UP).toPlainString();
}

@Mapping(target = "displayAmount", qualifiedBy = MoneyToString.class)
DisplayDto toDto(Account account);
```

Without the qualifier, MapStruct would pick any available `String bigDecimalToString(BigDecimal)` method — ambiguous if multiple exist.

---

## Q10: What happens if MapStruct generates a mapper that throws at runtime?

**A:** MapStruct's generated code includes `null` checks that throw `NullPointerException` if the source object is `null`. If:
```java
OneTimePaymentResponse response = mapper.toResponse(null);
// Throws NullPointerException in the generated impl
```

To handle this, you can add a null check in the service layer before calling the mapper, or annotate the mapper method with `@BeanMapping(nullValueMappingStrategy = NullValueMappingStrategy.RETURN_NULL)` to return `null` instead of throwing.

In the current project, `ProcessPaymentService` always returns a `PaymentResult` (never null) — so this is not a risk. But defensive coding in the mapper is still good practice.
