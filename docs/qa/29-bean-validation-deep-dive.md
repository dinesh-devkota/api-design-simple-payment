# Q&A 29 — Bean Validation (JSR-380) Deep Dive

> Every aspect of `@Valid`, `@NotNull`, `@Positive`, custom validators — the framework that rejects bad input before it touches your domain.

---

## Q1: What is JSR-380 and what is the relationship between it, Hibernate Validator, and Spring?

**A:**

- **JSR-380** (Bean Validation 2.0) is a Java specification (`jakarta.validation.*`). It defines annotations like `@NotNull`, `@Size`, `@Min`, `@Pattern` and the `Validator` interface.
- **Hibernate Validator** is the reference implementation — the JAR that actually executes the validation logic. Pulled transitively via `spring-boot-starter-web`.
- **Spring** integrates Bean Validation via `@Valid` on method parameters. When `@Valid` is present, Spring calls the Hibernate Validator engine before the method body executes.

**Without Spring:** You can use `Validator validator = Validation.buildDefaultValidatorFactory().getValidator(); Set<ConstraintViolation<T>> violations = validator.validate(object);`

---

## Q2: What is the difference between `@Valid` and `@Validated`?

**A:**

| | `@Valid` | `@Validated` |
|---|---|---|
| Spec | Jakarta Bean Validation (JSR-380) | Spring-specific |
| Supports groups? | No | Yes |
| Works on method params | Yes | Yes (via Spring AOP) |
| Works on nested objects | Yes (cascade) | Yes |
| Works on `@Service` beans | No — only in Spring MVC / WebFlux | Yes — Spring AOP wraps any `@Component` |

**Groups example:** Different validation rules for "create" vs "update":
```java
public interface OnCreate {}
public interface OnUpdate {}

public class UserRequest {
    @Null(groups = OnCreate.class)
    @NotNull(groups = OnUpdate.class)
    private Long id;
}

@PostMapping
public ResponseEntity<Void> create(@Validated(OnCreate.class) @RequestBody UserRequest req) { ... }
```

In this project: `@Valid` is used on `@RequestBody` in the controller — correct for MVC request validation without groups.

---

## Q3: What annotations are available on `OneTimePaymentRequest` and what do they each check?

**A:** Based on the OpenAPI-generated DTO:

| Field | Annotation | Validated condition |
|---|---|---|
| `userId` | `@NotBlank` | Not null, not empty, not whitespace-only |
| `paymentAmount` | `@NotNull` | Not null |
| `paymentAmount` | `@DecimalMin("0.01")` | At least 0.01 (minimum payment) |
| `paymentAmount` | `@Digits(integer=10, fraction=2)` | Max 10 integer digits, max 2 decimal places |

These annotations come from the `openapi.yaml` constraints:
```yaml
paymentAmount:
  type: number
  minimum: 0.01
  multipleOf: 0.01
```

The OpenAPI Generator translates `minimum: 0.01` → `@DecimalMin("0.01")` and `multipleOf: 0.01` → `@Digits(fraction=2)`.

---

## Q4: What happens when validation fails? Walk through the exception chain.

**A:**

1. Spring MVC calls `HandlerMethodArgumentResolver.resolveArgument()` which triggers `@Valid` on `@RequestBody`.
2. Hibernate Validator finds one or more constraint violations.
3. Spring throws `MethodArgumentNotValidException` (extends `BindException`).
4. Spring's `DispatcherServlet` looks for an `@ExceptionHandler` for this exception type.
5. `GlobalExceptionHandler.handleValidation(MethodArgumentNotValidException ex)` catches it.
6. The handler extracts `ex.getBindingResult().getAllErrors()` — each `ObjectError` or `FieldError` contains the field name and message.
7. Returns `ResponseEntity<ErrorResponse>` with HTTP 400.

**Example `ErrorResponse` for a blank `userId`:**
```json
{
  "message": "Validation failed",
  "errors": [
    { "field": "userId", "message": "must not be blank" }
  ]
}
```

---

## Q5: How do you write a custom constraint annotation?

**A:** Three components:

**1. The annotation:**
```java
@Documented
@Constraint(validatedBy = ValidCurrencyValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidCurrency {
    String message() default "Invalid currency code";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
```

**2. The validator:**
```java
public class ValidCurrencyValidator implements ConstraintValidator<ValidCurrency, String> {
    private static final Set<String> VALID = Set.of("USD", "EUR", "GBP");
    
    @Override
    public boolean isValid(String value, ConstraintValidatorContext ctx) {
        return value == null || VALID.contains(value.toUpperCase());
    }
}
```

**3. Usage:**
```java
public class OneTimePaymentRequest {
    @ValidCurrency
    private String currency;
}
```

`isValid` should return `true` for `null` — let `@NotNull` handle null checks separately (single responsibility for constraints).

---

## Q6: How does `@Valid` cascade into nested objects?

**A:** `@Valid` on a field triggers validation of that nested object's own constraints:

```java
public class OneTimePaymentRequest {
    @Valid  // triggers validation of PaymentDetails' constraints
    @NotNull
    private PaymentDetails details;
}

public class PaymentDetails {
    @NotNull
    @Positive
    private BigDecimal amount;
}
```

Without `@Valid` on `details`, the nested `@Positive` on `amount` would be ignored — only the top-level `@NotNull` on `details` itself would run.

**In collections:**
```java
@Valid
private List<@NotBlank String> tags;  // validates each element in the list
```

---

## Q7: What is the difference between `@NotNull`, `@NotEmpty`, and `@NotBlank`?

**A:**

| Annotation | Null | Empty (`""`) | Whitespace-only (`"  "`) | Works on |
|---|---|---|---|---|
| `@NotNull` | ❌ fails | ✅ passes | ✅ passes | Any type |
| `@NotEmpty` | ❌ fails | ❌ fails | ✅ passes | String, Collection, Map, Array |
| `@NotBlank` | ❌ fails | ❌ fails | ❌ fails | String only |

**Best practice:** For String fields that users fill in (names, IDs, descriptions), use `@NotBlank`. `@NotEmpty` allows whitespace strings which are semantically empty.

---

## Q8: Can you validate method return values?

**A:** Yes, with `@Validated` on the class and `@NotNull` on the return type:

```java
@Validated
@Service
public class ProcessPaymentService {
    @NotNull
    public PaymentResult process(String userId, BigDecimal amount) {
        // ...
    }
}
```

Spring AOP intercepts the return value and validates it. If `process()` returns `null`, `ConstraintViolationException` is thrown.

**Use case:** Contract enforcement — asserting that a service method never returns null, catching programmer errors rather than input errors.

---

## Q9: What is the performance cost of Bean Validation?

**A:** Hibernate Validator uses reflection to read annotations and field values. For a small DTO like `OneTimePaymentRequest` (2-3 fields), the cost is negligible — microseconds.

**At scale:** For DTOs with 50+ fields and complex nested objects validated on every request at 10,000 RPS, validation overhead can become measurable. Mitigation:
- Use `@Validated` groups to run only relevant constraints per operation.
- Pre-compile the validator metadata with `ValidatorFactory` (already done by Spring's auto-configuration).
- Profile before optimising — premature optimisation is the root of all evil.

---

## Q10: What is the missing validation in this project, and what is the risk?

**A:** The domain service re-validates `paymentAmount > 0` (`InvalidPaymentAmountException`) but the controller validates `paymentAmount >= 0.01`. These are not the same:

- Controller: `paymentAmount >= 0.01` — allows `0.001` (3 decimal places) if `@DecimalMin` but not `@Digits` is used.
- Domain: `paymentAmount > BigDecimal.ZERO` — allows `0.0001`.

**The risk:** If an amount like `0.001` passes controller validation (`@DecimalMin("0.01")` passes for `0.001`? No — `0.001 < 0.01` so it fails the `@DecimalMin`). Actually `@DecimalMin("0.01", inclusive=true)` would reject `0.001`. The domain validation is a defence-in-depth double check.

**Missing validation:** No maximum payment amount limit. An attacker could submit `paymentAmount = 999999999.99` — this passes all current validators. The only protection is the `InsufficientBalanceException` if the account has less than that amount. A `@DecimalMax` constraint should be added.
