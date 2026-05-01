# Design Patterns in Action - Code Examples & Flow

## Complete Flow: How Patterns Work Together

### Single Payment Request Journey

```
┌──────────────────────────────────────────────────────────────────────┐
│                    HTTP REQUEST: POST /one-time-payment              │
│                    {"userId": "user-1", "paymentAmount": 10.00}      │
└──────────────────────────────────────────────────────────────────────┘
                                 ↓
┌──────────────────────────────────────────────────────────────────────┐
│ 1. PaymentController (DRIVING ADAPTER)                               │
│                                                                      │
│ @RestController                                                      │
│ public class PaymentController implements PaymentApi {              │
│                                                                      │
│     @Override                                                        │
│     public ResponseEntity<OneTimePaymentResponse> oneTimePayment(   │
│         @Valid @RequestBody OneTimePaymentRequest request,  ← BEAN  │
│         String idempotencyKey) {                            VALIDATION
│                                                                      │
│         MDC.put("userId", request.getUserId());                     │
│         try {                                                        │
│             // Pattern: Dependency Injection                         │
│             // (Constructor injected by Spring)                      │
│             OneTimePaymentResponse response = idempotencyGuard.resolve(
│                 idempotencyKey,                                      │
│                 OneTimePaymentResponse.class,                        │
│                 () -> paymentResponseMapper.toResponse(             │
│                     processPaymentUseCase.process(                  │
│                         request.getUserId(),                        │
│                         request.getPaymentAmount()                  │
│                     )                                               │
│                 )                                                   │
│             );                                                      │
│             return ResponseEntity.ok(response);                     │
│         } finally {                                                 │
│             MDC.remove("userId");                                   │
│         }                                                           │
│     }                                                               │
│                                                                      │
│ Patterns Applied:                                                   │
│  ✓ Dependency Injection (@RequiredArgsConstructor)                 │
│  ✓ Strategy Pattern (Supplier<T>)                                  │
│  ✓ Mapper Pattern (MapStruct)                                      │
│  ✓ Template Method (via IdempotencyGuard.resolve)                  │
└──────────────────────────────────────────────────────────────────────┘
                                 ↓
┌──────────────────────────────────────────────────────────────────────┐
│ 2. IdempotencyGuard (STRATEGY + TEMPLATE METHOD)                    │
│                                                                      │
│ public <T> T resolve(String key, Class<T> type, Supplier<T> supplier) {
│     // Pattern: Template Method (Algorithm skeleton)                │
│     // Step 1: Validate key                                         │
│     if (key == null || key.isBlank()) {                            │
│         return supplier.get();  // Skip caching                      │
│     }                                                               │
│                                                                      │
│     // Step 2: Check cache                                          │
│     Optional<T> cached = idempotencyStore.find(key, type);         │
│     if (cached.isPresent()) {                                       │
│         log.info("Idempotency cache hit: key={}", key);            │
│         return cached.get();  // Return cached ✓                    │
│     }                                                               │
│                                                                      │
│     // Step 3: Execute (STRATEGY - the varying part)               │
│     T result = supplier.get();  // ← supplier is the strategy      │
│                                                                      │
│     // Step 4: Store result                                         │
│     idempotencyStore.store(key, result);                           │
│     return result;                                                 │
│ }                                                                   │
│                                                                      │
│ Patterns Applied:                                                   │
│  ✓ Strategy Pattern (Supplier<T> as strategy)                      │
│  ✓ Template Method (Fixed steps with optional variation)           │
│  ✓ Adapter Pattern (Uses IdempotencyStoreSpi abstraction)          │
│  ✓ Optional Pattern (Optional<T> for cache result)                 │
└──────────────────────────────────────────────────────────────────────┘
                                 ↓
                        supplier.get() called
                        = processPaymentUseCase.process()
                                 ↓
┌──────────────────────────────────────────────────────────────────────┐
│ 3. ProcessPaymentService (DOMAIN LOGIC - ORCHESTRATOR)              │
│                                                                      │
│ @Service                                                            │
│ public class ProcessPaymentService implements ProcessPaymentUseCase{
│                                                                      │
│     private final AccountSpi accountSpi;                            │
│     private final MatchCalculationService matchCalcService;         │
│     private final DueDateCalculationService dueDateService;         │
│     private final Clock clock;  ← Factory pattern result            │
│                                                                      │
│     public PaymentResult process(String userId,                     │
│                                   BigDecimal paymentAmount) {       │
│         // Validation (guard clause)                                │
│         if (paymentAmount.compareTo(ZERO) <= 0) {                   │
│             throw new InvalidPaymentAmountException(...);           │
│         }                                                           │
│                                                                      │
│         // Pattern: Repository (via SPI abstraction)                │
│         // Depends on AccountSpi interface, not Redis               │
│         Account account = accountSpi.findById(userId)               │
│             .orElseThrow(                                           │
│                 () -> new AccountNotFoundException(...)             │
│             );  ← Optional Pattern                                  │
│                                                                      │
│         // Pattern: Strategy (MatchCalculationService)              │
│         int matchPercentage = matchCalcService                      │
│             .getMatchPercentage(paymentAmount);                     │
│         BigDecimal matchAmount = matchCalcService                   │
│             .calculateMatchAmount(paymentAmount);                   │
│                                                                      │
│         BigDecimal totalDeduction = paymentAmount.add(matchAmount); │
│                                                                      │
│         // Business rule validation                                 │
│         if (totalDeduction.compareTo(account.getBalance()) > 0) {  │
│             throw new InsufficientBalanceException(...);            │
│         }                                                           │
│                                                                      │
│         BigDecimal newBalance = account.getBalance()                │
│             .subtract(totalDeduction)                               │
│             .setScale(2, HALF_UP);                                  │
│                                                                      │
│         // Pattern: Factory (Clock injection)                       │
│         LocalDate today = LocalDate.now(clock);                     │
│         LocalDate nextDueDate = dueDateService                      │
│             .calculateDueDate(today);  ← Strategy pattern           │
│                                                                      │
│         account.setBalance(newBalance);                             │
│                                                                      │
│         // Pattern: Repository (via SPI)                            │
│         accountSpi.save(account);                                   │
│                                                                      │
│         // Pattern: Value Object (immutable record)                 │
│         return new PaymentResult(                                   │
│             userId,                                                │
│             account.getBalance(), /* before */                      │
│             paymentAmount,                                          │
│             matchPercentage,                                        │
│             matchAmount,                                            │
│             newBalance,                                             │
│             nextDueDate,                                            │
│             today                                                   │
│         );                                                          │
│     }                                                               │
│ }                                                                   │
│                                                                      │
│ Patterns Applied:                                                   │
│  ✓ Dependency Injection (strategies injected)                       │
│  ✓ Repository Pattern (AccountSpi abstraction)                      │
│  ✓ Strategy Pattern (MatchCalc, DueDateCalc services)              │
│  ✓ Factory Pattern (Clock injection)                               │
│  ✓ Optional Pattern (Optional<Account> from findById)              │
│  ✓ Value Object Pattern (PaymentResult record)                     │
│  ✓ NO Hexagonal violation (depends only on SPIs)                   │
└──────────────────────────────────────────────────────────────────────┘
                                 ↓
                  3a. AccountSpi.findById(userId)
                       (Repository delegation)
                                 ↓
┌──────────────────────────────────────────────────────────────────────┐
│ 3a. AccountAdapter (DRIVEN ADAPTER + REPOSITORY PATTERN)             │
│                                                                      │
│ @Component                                                          │
│ public class AccountAdapter implements AccountSpi {                 │
│                                                                      │
│     private final AccountRedisRepository repository;                │
│     private final AccountEntityMapper mapper;                        │
│                                                                      │
│     @Override                                                       │
│     public Optional<Account> findById(String userId) {              │
│         // Dependencies injected (Dependency Injection)              │
│                                                                      │
│         // Queries Redis via Spring Data                            │
│         // Pattern: Repository (Spring Data CrudRepository)         │
│         Optional<AccountEntity> entity =                            │
│             repository.findById(userId);                            │
│                                                                      │
│         // Pattern: Mapper (MapStruct)                              │
│         // Converts Redis entity to domain model                    │
│         return entity.map(mapper::toDomain);  ← Optional + Mapper   │
│     }                                                               │
│                                                                      │
│     @Override                                                       │
│     public Account save(Account account) {                          │
│         // Pattern: Mapper (MapStruct - both directions)            │
│         AccountEntity entity = mapper.toEntity(account);            │
│         AccountEntity saved = repository.save(entity);              │
│         return mapper.toDomain(saved);                              │
│     }                                                               │
│ }                                                                   │
│                                                                      │
│ Patterns Applied:                                                   │
│  ✓ Adapter Pattern (implements AccountSpi, uses Redis)             │
│  ✓ Repository Pattern (via Spring Data)                            │
│  ✓ Mapper Pattern (AccountEntityMapper)                            │
│  ✓ Dependency Injection (constructor injected)                      │
│  ✓ Optional Pattern (Optional<Account> return)                     │
└──────────────────────────────────────────────────────────────────────┘
                                 ↓
                   3a.1 AccountEntityMapper
                        (MAPPER PATTERN)
                                 ↓
┌──────────────────────────────────────────────────────────────────────┐
│ 3a.1 AccountEntityMapper (MAPPER PATTERN)                            │
│                                                                      │
│ // Interface (written by developer)                                 │
│ @Mapper(componentModel = "spring")                                  │
│ public interface AccountEntityMapper {                              │
│     Account toDomain(AccountEntity entity);                         │
│     AccountEntity toEntity(Account account);                        │
│ }                                                                   │
│                                                                      │
│ // Implementation (generated by MapStruct at compile time)          │
│ @Component                                                          │
│ public class AccountEntityMapperImpl implements AccountEntityMapper {│
│     public Account toDomain(AccountEntity entity) {                 │
│         if (entity == null) return null;                            │
│         Account account = Account.builder()  ← Builder pattern      │
│             .userId(entity.getUserId())                            │
│             .balance(entity.getBalance())                          │
│             .build();                                              │
│         return account;                                            │
│     }                                                               │
│ }                                                                   │
│                                                                      │
│ Key Benefits:                                                       │
│  ✓ Zero reflection (compile-time generation)                       │
│  ✓ Type-safe                                                        │
│  ✓ Auto-registers as Spring bean                                   │
│  ✓ Works bidirectionally                                            │
└──────────────────────────────────────────────────────────────────────┘
                                 ↓
           Back to ProcessPaymentService with Account
                   and calculated PaymentResult
                                 ↓
┌──────────────────────────────────────────────────────────────────────┐
│ 4. PaymentResponseMapper (MAPPER PATTERN)                            │
│                                                                      │
│ // Converts domain PaymentResult to REST DTO                        │
│ @Mapper(componentModel = "spring")                                  │
│ public interface PaymentResponseMapper {                            │
│                                                                      │
│     // MapStruct generates this implementation                      │
│     // Maps fields by name: PaymentResult → OneTimePaymentResponse │
│     OneTimePaymentResponse toResponse(PaymentResult result);        │
│ }                                                                   │
│                                                                      │
│ // Generated implementation (simplified):                           │
│ @Component                                                          │
│ public class PaymentResponseMapperImpl {                             │
│     public OneTimePaymentResponse toResponse(PaymentResult result) {│
│         OneTimePaymentResponse response = new                       │
│             OneTimePaymentResponse();                               │
│         response.setNewBalance(result.newBalance());                │
│         response.setPreviousBalance(result.previousBalance());      │
│         response.setNextPaymentDueDate(result.nextDueDate());       │
│         response.setPaymentDate(result.paymentDate());              │
│         // Note: userId and matchPercentage NOT included            │
│         // (not in DTO contract)                                    │
│         return response;                                           │
│     }                                                               │
│ }                                                                   │
│                                                                      │
│ Patterns Applied:                                                   │
│  ✓ Mapper Pattern (MapStruct)                                       │
│  ✓ Dependency Injection (Spring bean)                               │
└──────────────────────────────────────────────────────────────────────┘
                                 ↓
                   Mapper returns OneTimePaymentResponse
                 (idempotencyGuard stores this in cache)
                                 ↓
┌──────────────────────────────────────────────────────────────────────┐
│ 5. RedisIdempotencyStore (ADAPTER PATTERN)                           │
│                                                                      │
│ @Component                                                          │
│ public class RedisIdempotencyStore implements IdempotencyStoreSpi { │
│                                                                      │
│     private final StringRedisTemplate redisTemplate;                │
│     private final ObjectMapper objectMapper;                        │
│                                                                      │
│     @Override                                                       │
│     public void store(String key, Object value) {                   │
│         try {                                                       │
│             // Convert DTO to JSON (any type supported)             │
│             String json = objectMapper.writeValueAsString(value);   │
│                                                                      │
│             // Store in Redis with 24-hour TTL                      │
│             redisTemplate.opsForValue().set(                        │
│                 "idempotency:" + key,                               │
│                 json,                                              │
│                 Duration.ofHours(24)                               │
│             );                                                      │
│             log.debug("Cached: key={}", key);                       │
│         } catch (JsonProcessingException e) {                       │
│             // Fail-safe: don't block payment if cache fails        │
│             log.error("Failed to cache", e);                        │
│         }                                                           │
│     }                                                               │
│                                                                      │
│     @Override                                                       │
│     public <T> Optional<T> find(String key, Class<T> type) {       │
│         String json = redisTemplate.opsForValue()                   │
│             .get("idempotency:" + key);                             │
│         if (json == null) {                                         │
│             return Optional.empty();                                │
│         }                                                           │
│         try {                                                       │
│             // Deserialize JSON to requested type                   │
│             return Optional.of(objectMapper.readValue(json, type)); │
│         } catch (JsonProcessingException e) {                       │
│             log.error("Failed to deserialize", e);                  │
│             return Optional.empty();  // Fail gracefully            │
│         }                                                           │
│     }                                                               │
│ }                                                                   │
│                                                                      │
│ Patterns Applied:                                                   │
│  ✓ Adapter Pattern (implements IdempotencyStoreSpi)                │
│  ✓ Dependency Injection (constructor injected)                      │
│  ✓ Template Method (fail-safe error handling pattern)              │
│  ✓ Optional Pattern (Optional<T> return)                           │
└──────────────────────────────────────────────────────────────────────┘
                                 ↓
           IdempotencyGuard caches response & returns it
                                 ↓
┌──────────────────────────────────────────────────────────────────────┐
│ 6. Exception Handling (ADVICE PATTERN)                               │
│                                                                      │
│ // If ANY exception thrown during payment,                          │
│ // GlobalExceptionHandler catches it globally                       │
│                                                                      │
│ @RestControllerAdvice                                              │
│ public class GlobalExceptionHandler {                               │
│                                                                      │
│     @ExceptionHandler(InvalidPaymentAmountException.class)          │
│     public ResponseEntity<ErrorResponse> handleInvalidAmount(       │
│         InvalidPaymentAmountException ex) {                         │
│         return buildResponse(                                       │
│             HttpStatus.BAD_REQUEST,  // 400                         │
│             ex.getMessage(),                                        │
│             null                                                    │
│         );                                                          │
│     }                                                               │
│                                                                      │
│     @ExceptionHandler(AccountNotFoundException.class)               │
│     public ResponseEntity<ErrorResponse> handleNotFound(            │
│         AccountNotFoundException ex) {                              │
│         return buildResponse(                                       │
│             HttpStatus.NOT_FOUND,  // 404                           │
│             ex.getMessage(),                                        │
│             null                                                    │
│         );                                                          │
│     }                                                               │
│                                                                      │
│     @ExceptionHandler(InsufficientBalanceException.class)           │
│     public ResponseEntity<ErrorResponse> handleInsufficientBalance( │
│         InsufficientBalanceException ex) {                          │
│         return buildResponse(                                       │
│             HttpStatus.UNPROCESSABLE_ENTITY,  // 422                │
│             ex.getMessage(),                                        │
│             null                                                    │
│         );                                                          │
│     }                                                               │
│                                                                      │
│     @ExceptionHandler(Exception.class)                              │
│     public ResponseEntity<ErrorResponse> handleGeneric(             │
│         Exception ex) {                                             │
│         log.error("Unexpected error", ex);  // Stack trace server-side only
│         return buildResponse(                                       │
│             HttpStatus.INTERNAL_SERVER_ERROR,  // 500               │
│             "An unexpected error occurred",                         │
│             null                                                    │
│         );  /* No stack trace exposed to client */                  │
│     }                                                               │
│                                                                      │
│     private ResponseEntity<ErrorResponse> buildResponse(            │
│         HttpStatus status,                                          │
│         String message,                                             │
│         List<String> errors) {                                      │
│         // Pattern: Value Object                                    │
│         ErrorResponse body = new ErrorResponse()                    │
│             .timestamp(OffsetDateTime.now(ZoneOffset.UTC))          │
│             .status(status.value())                                 │
│             .error(status.getReasonPhrase())                        │
│             .message(message)                                       │
│             .errors(errors);                                        │
│         return ResponseEntity.status(status).body(body);            │
│     }                                                               │
│ }                                                                   │
│                                                                      │
│ Patterns Applied:                                                   │
│  ✓ Advice Pattern (@RestControllerAdvice)                          │
│  ✓ Value Object (ErrorResponse)                                    │
│  ✓ Factory (private buildResponse method)                           │
│  ✓ Template Method (common response building pattern)              │
└──────────────────────────────────────────────────────────────────────┘
                                 ↓
┌──────────────────────────────────────────────────────────────────────┐
│ 7. HTTP Response Sent Back to Client                                 │
│                                                                      │
│ Success Case:                                                        │
│ ────────────                                                        │
│ HTTP 200 OK                                                         │
│ {                                                                   │
│   "previousBalance": 100.00,                                        │
│   "newBalance": 89.70,                                              │
│   "nextPaymentDueDate": "2026-04-28",                               │
│   "paymentDate": "2026-04-13"                                       │
│ }                                                                   │
│                                                                      │
│ Error Case (e.g., Account Not Found):                               │
│ ──────────────────────────────────────                              │
│ HTTP 404 Not Found                                                  │
│ {                                                                   │
│   "timestamp": "2026-04-25T10:15:30Z",                              │
│   "status": 404,                                                    │
│   "error": "Not Found",                                             │
│   "message": "Account not found for userId: user-999",              │
│   "errors": null                                                    │
│ }                                                                   │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Pattern Interaction Examples

### Example 1: Duplicate Request with Idempotency Key

```
First Request: idempotencyKey = "abc-123"
  │
  ├─1. Controller calls idempotencyGuard.resolve()
  │
  ├─2. Template Method checks: key != null ✓
  │
  ├─3. Strategy checks cache: NOT FOUND
  │
  ├─4. Supplier executes: processPaymentUseCase.process()
  │    ├─ Factory: Gets Clock
  │    ├─ Strategy: Calculates match %
  │    ├─ Strategy: Calculates due date
  │    ├─ Repository: Loads account
  │    ├─ Repository: Saves account
  │    └─ Value Object: Returns PaymentResult
  │
  ├─5. Mapper: Converts PaymentResult → OneTimePaymentResponse
  │
  ├─6. Adapter: Stores response in Redis cache (24h TTL)
  │    RedisIdempotencyStore.store("abc-123", response)
  │
  └─7. Response sent to client
       Balance: $89.70

               [15 minutes later]

Second Request: SAME idempotencyKey = "abc-123"
  │
  ├─1. Controller calls idempotencyGuard.resolve()
  │
  ├─2. Template Method checks: key != null ✓
  │
  ├─3. Strategy checks cache: FOUND ✓
  │    RedisIdempotencyStore.find("abc-123", OneTimePaymentResponse.class)
  │
  ├─4. Supplier NOT called (optimization!)
  │    Payment logic skipped entirely
  │    Database not accessed
  │
  ├─5. Mapper NOT called (cached response used)
  │
  ├─6. Cache NOT updated (still fresh)
  │
  └─7. Response sent to client immediately
       Balance: $89.70 (SAME - no double-deduction!)
       Latency: <10ms (cache hit vs 100+ms for actual processing)
```

### Example 2: Error Path

```
Request: paymentAmount = 0

  1. Validation in PaymentController:
     @Valid @RequestBody OneTimePaymentRequest
     └─ Bean Validation fails: paymentAmount <= 0
     
  2. Spring throws MethodArgumentNotValidException
  
  3. GlobalExceptionHandler catches:
     @ExceptionHandler(MethodArgumentNotValidException.class)
     └─ Returns 400 Bad Request with field errors
  
  4. Response:
     {
       "status": 400,
       "message": "Validation failed",
       "errors": ["paymentAmount: must be greater than 0"]
     }
  
     └─ No exception thrown in service
        PaymentService.process() never called
        Database not touched
        Cache not checked

Request: userId = "nonexistent"

  1. Controller calls idempotencyGuard.resolve()
  
  2. Supplier executes: processPaymentService.process()
  
  3. In ProcessPaymentService:
     account = accountSpi.findById("nonexistent")
               .orElseThrow(() -> new AccountNotFoundException(...))
  
  4. Optional is empty, exception thrown
  
  5. GlobalExceptionHandler catches:
     @ExceptionHandler(AccountNotFoundException.class)
     └─ Returns 404 Not Found
  
  6. Response:
     HTTP 404
     {
       "status": 404,
       "message": "Account not found for userId: nonexistent"
     }
```

---

## Pattern Relationships Diagram

```
                    CONTROLLER
                        ↓
                (Dependency Injection)
                        ↓
        ┌───────────────┼───────────────┐
        ↓               ↓               ↓
    IDEMPOTENCY    MAPPER          EXCEPTION
    GUARD          (MapStruct)      HANDLER
    (Strategy +     │          (Advice Pattern)
     Template      Converts           │
     Method)       Domain→DTO         │
        │          ↓                  Catches
        │          REST              Exceptions
        ↓          Response
    SUPPLIER ──────────────→ CLIENT
    (the actual
     payment work)
        │
        ├─ FACTORY (Clock)
        │
        ├─ STRATEGY (Match %)
        │
        ├─ STRATEGY (Due Date)
        │
        ├─ REPOSITORY ──→ ADAPTER ──→ Spring Data ──→ Redis
        │                       ↓
        │                   MAPPER
        │             (Entity ↔ Domain)
        │
        └─ VALUE OBJECT (PaymentResult)


Flow of Patterns in a Single Request:
────────────────────────────────────

[CONTROLLER] 
    uses (Dependency Injection)
    ↓
[IDEMPOTENCY GUARD: Strategy + Template Method]
    checks cache via (Adapter Pattern)  ───┐
    if miss, executes supplier via         │
    ↓                                       │
[SERVICE: Orchestrator]                     │
    uses (Strategy Pattern) × 2             │
    uses (Dependency Injection)             │
    uses (Factory Pattern)                  │
    uses (Repository via SPI)               │
    uses (Adapter Pattern)                  │
    uses (Mapper Pattern)                   │
    returns (Value Object)                  │
    ↓                                       │
[MAPPER]                                    │
    converts Domain → DTO                   │
    ↓                                       │
[IDEMPOTENCY: Store via Adapter] ←──────────┘
    ↓
[RESPONSE SENT]

If Exception at Any Point:
    ↓
[GLOBAL EXCEPTION HANDLER: Advice Pattern]
    converts Exception → HTTP Response
    uses (Value Object) for error envelope
    ↓
[ERROR RESPONSE SENT]
```

---

## Performance Impact of Patterns

```
Pattern Overhead Analysis:

Pattern                  Runtime Cost        Notes
──────────────────────────────────────────────────────────
Hexagonal              Negligible            Method calls same cost
Dependency Injection   Negligible            Done at startup
Repository             Negligible            Direct method call
Strategy               Negligible            Polymorphic call
Mapper (MapStruct)     Negligible            Generated, no reflection
Adapter                Negligible            Direct delegation
Factory                Negligible            Done once at startup
Template Method        Negligible            Control flow only
Exception Advice       <1ms per exception    Only on error path
Builder                <1μs per object       Compile time generation
Optional               <1μs per operation    Small wrapper object
Value Object (Record)  Negligible            Optimized by JVM

Optimization Example:
─────────────────────

Without Caching:     processPayment() takes 50ms
With Idempotency:    Cache hit takes <5ms (10x faster)
                     Saves 90% latency on retries

Therefore:
✓ Patterns reduce actual runtime overhead
✓ Benefits (testability, maintainability) >> costs
```

---

## Key Takeaways

### Synergies (Patterns That Work Well Together)

```
1. HEXAGONAL + REPOSITORY + ADAPTER
   └─ Perfect isolation: domain knows nothing of infrastructure
   └─ Easy testing: mock the repository interface
   └─ Easy migration: swap Redis for Oracle in adapter only

2. DEPENDENCY INJECTION + FACTORY
   └─ Flexible configuration: runtime selection of implementation
   └─ Testable: inject test doubles easily
   └─ Clean code: no if-else chains in application code

3. MAPPER + STRATEGY + TEMPLATE METHOD
   └─ Reusable pattern: cache-check → execute → store
   └─ Type-safe: generics with MapStruct
   └─ Extensible: add new strategies without touching pattern

4. EXCEPTION ADVICE + VALUE OBJECT
   └─ Consistent errors: same shape for all exceptions
   └─ No duplication: one place to change error format
   └─ API contract: clear error envelope in documentation
```

### Anti-Patterns Avoided

```
❌ NOT USED (and why):
  - Singleton: Spring manages lifecycle
  - Service Locator: Dependency injection is cleaner
  - Abstract Factory: Overkill when Spring can do it
  - Proxy: Spring AOP handles cross-cutting concerns
  - Observer: Don't need pub-sub for simple operations
  
✓ CORRECT APPROACH:
  Use only patterns where they solve real problems
  Don't use patterns "just in case"
  Measure complexity vs benefit
```

This shows how professional enterprise applications use patterns pragmatically, not dogmatically.

