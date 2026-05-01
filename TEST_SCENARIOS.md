# Test Scenarios

All test scenarios for the Customer Care API, grouped by layer and concern.

---

## 1. Match Calculation — `MatchCalculationServiceTest`

### 1.1 `getMatchPercentage` — tier boundaries

| Scenario | Input `paymentAmount` | Expected `matchPercentage` |
|---|---|---|
| Minimum valid (low tier) | `0.01` | `1` |
| Top of low tier | `9.99` | `1` |
| Bottom of mid tier | `10.00` | `3` |
| Top of mid tier | `49.99` | `3` |
| Bottom of high tier | `50.00` | `5` |
| Mid high tier | `100.00` | `5` |
| Large amount | `999.99` | `5` |

### 1.2 `calculateMatchAmount` — rounded to 2 decimal places (HALF_UP)

| Scenario | Input `paymentAmount` | Expected `matchAmount` |
|---|---|---|
| Tiny amount (rounds to zero) | `0.01` | `0.00` |
| Top of low tier | `9.99` | `0.10` |
| Bottom of mid tier | `10.00` | `0.30` |
| Top of mid tier | `49.99` | `1.50` |
| Bottom of high tier | `50.00` | `2.50` |
| High tier | `100.00` | `5.00` |

---

## 2. Due Date Calculation — `DueDateCalculationServiceTest`

Due date = payment date + 15 days, shifted to Monday if the result is a Saturday or Sunday.

The test uses a consecutive one-week block (2026-04-10 → 2026-04-16). Because 15 mod 7 = 1 this steps the result day-of-week forward by one, covering all seven `DayOfWeek` outcomes in a single `@CsvSource`.

| Scenario | Input `paymentDate` | Raw +15 | Day of week | Expected `dueDate` |
|---|---|---|---|---|
| Lands on Saturday — shift +2 | `2026-04-10` | `2026-04-25` | Saturday | `2026-04-27` |
| Lands on Sunday — shift +1 | `2026-04-11` | `2026-04-26` | Sunday | `2026-04-27` |
| Lands on Monday — no shift | `2026-04-12` | `2026-04-27` | Monday | `2026-04-27` |
| Lands on Tuesday — no shift | `2026-04-13` | `2026-04-28` | Tuesday | `2026-04-28` |
| Lands on Wednesday — no shift | `2026-04-14` | `2026-04-29` | Wednesday | `2026-04-29` |
| Lands on Thursday — no shift | `2026-04-15` | `2026-04-30` | Thursday | `2026-04-30` |
| Lands on Friday — no shift | `2026-04-16` | `2026-05-01` | Friday | `2026-05-01` |

---

## 3. Payment Use Case — `ProcessPaymentServiceTest`

### 3.1 Happy path

| Scenario | `userId` | Initial balance | `paymentAmount` | Expected `matchPercentage` | Expected `matchAmount` | Expected `newBalance` |
|---|---|---|---|---|---|---|
| Mid-tier match | `user-1` | `100.00` | `10.00` | `3` | `0.30` | `89.70` |
| High-tier match | `user-2` | `500.00` | `75.00` | `5` | `3.75` | `421.25` |
| Due date populated | `user-1` | `100.00` | `10.00` | — | — | `nextPaymentDueDate` is non-null |

### 3.2 Error scenarios

| Scenario | `userId` | `paymentAmount` | Initial balance | Expected exception |
|---|---|---|---|---|
| Zero amount | `user-1` | `0.00` | — | `InvalidPaymentAmountException` |
| Negative amount | `user-1` | `-5.00` | — | `InvalidPaymentAmountException` |
| Unknown userId | `unknown` | `10.00` | — | `AccountNotFoundException` (message contains `"unknown"`) |
| Insufficient balance | `user-1` | `100.00` | `50.00` | `InsufficientBalanceException` (payment `100.00` + match `5.00` > balance `50.00`) |
| Exact balance (boundary) | `user-1` | `95.24` | `100.00` | Succeeds — payment `95.24` + match `4.76` = `100.00` exactly; `newBalance = 0.00` |

---

## 4. Integration Tests — `PaymentControllerIntegrationTest`

Full Spring Boot context with embedded Redis (port 6381).

**Seeded accounts before each test:**

| `userId`   | Balance  |
|------------|----------|
| `user-001` | `100.00` |
| `user-002` | `500.00` |
| `user-low` | `50.00`  |

### 4.1 `POST /one-time-payment` — happy path

| Scenario | Request | Expected status | Expected `newBalance` |
|---|---|---|---|
| Low-tier match | `userId: user-low`, `paymentAmount: 5.00` | `200 OK` | `44.95` |
| Mid-tier match | `userId: user-001`, `paymentAmount: 10.00` | `200 OK` | `89.70` |
| High-tier match | `userId: user-002`, `paymentAmount: 75.00` | `200 OK` | `421.25` |
| Response shape (no internal fields) | `userId: user-001`, `paymentAmount: 10.00` | `200 OK` | `nextPaymentDueDate` present; `matchPercentage`, `previousBalance`, `userId` absent |

### 4.2 `POST /one-time-payment` — validation failures (400)

| Scenario | Request | Expected status | Notes |
|---|---|---|---|
| Zero payment amount | `userId: user-001`, `paymentAmount: 0` | `400 Bad Request` | Bean Validation (`minimum: 0.01`) |
| Negative payment amount | `userId: user-001`, `paymentAmount: -5.00` | `400 Bad Request` | Bean Validation |
| Missing userId | `paymentAmount: 10.00` (no `userId` field) | `400 Bad Request` | Bean Validation (`minLength: 1`) |

### 4.3 `POST /one-time-payment` — not found (404)

| Scenario | Request | Expected status | Expected `status` field in body |
|---|---|---|---|
| Unknown userId | `userId: unknown-user`, `paymentAmount: 10.00` | `404 Not Found` | `404` |

### 4.4 `POST /one-time-payment` — insufficient balance (422)

| Scenario | Request | Seeded balance | Expected status | Notes |
|---|---|---|---|---|
| Payment + match exceeds balance | `userId: user-low`, `paymentAmount: 50.00` | `50.00` | `422 Unprocessable Entity` | Payment `50.00` + match `2.50` (5%) = `52.50` > `50.00` |

### 4.5 `POST /one-time-payment` — idempotency

| Scenario | Request | `Idempotency-Key` header | Expected status | Notes |
|---|---|---|---|---|
| Duplicate request replays cached response | `userId: user-001`, `paymentAmount: 10.00` | `test-key-001` | `200 OK` | First call processes payment (balance → `89.70`). Second call with same key returns cached `89.70` without deducting again. |

---

## 5. Health Check — `HelloControllerTest`

| Scenario | Request | Expected status | Expected body |
|---|---|---|---|
| Smoke test | `GET /hello` | `200 OK` | `"Hello from customer-care-api!"` |

---

## 6. Error Response Shape

All non-2xx responses conform to:

```json
{
  "timestamp": "<ISO-8601 date-time>",
  "status": <HTTP status code>,
  "error": "<HTTP reason phrase>",
  "message": "<human-readable message>",
  "errors": ["<field>: <constraint message>"]
}
```

`errors` is only populated for `400` validation failures; it is absent (or `null`) on `404`, `422`, and `500`.

---

## 7. Business Rule Examples (from spec)

| Payment date | Initial balance | Payment | Match % | Match amount | New balance | Due date |
|---|---|---|---|---|---|---|
| 2022-03-14 | `100.00` | `10.00` | 3% | `0.30` | `89.70` | `2022-03-29` (Tue) |
| 2022-04-08 | `500.00` | `75.00` | 5% | `3.75` | `421.25` | `2022-04-25` (Mon, shifted from Sat) |
