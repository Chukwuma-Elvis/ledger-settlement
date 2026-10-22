# Research Notes

## Primary documentation: how the datasource is pointed at the container

**Source:** Spring Boot Reference Documentation
**Page title:** Testcontainers :: Spring Boot
**URL:** https://docs.spring.io/spring-boot/reference/testing/testcontainers.html
**Section:** Service Connections
**Spring Boot version used in this project:** 4.1.1
**Date read:** 2026-10-22

> "When doing so, the connection details take precedence over any connection-related
> configuration properties."
>
> (Service Connections section, first paragraph. "When doing so" refers to Spring Boot's
> auto-configuration consuming a service connection's details.)

### What this means for `PaymentControllerIT` (in my own words)

- `@ServiceConnection` on the static `PostgreSQLContainer` field makes Spring Boot create a
  `JdbcConnectionDetails` bean from the running container (the docs' factory table matches it on
  containers of type `JdbcDatabaseContainer`, which `PostgreSQLContainer` is).
- The DataSource auto-configuration uses that bean **instead of** the `spring.datasource.*`
  properties, so the URL, username and password from `application.yml` and `.env` are overridden
  for the test. A `FlywayConnectionDetails` bean is created the same way, so Flyway migrates the
  container too.
- **Evidence from our own run** (`mvn verify`, 2026-10-22): the test log shows
  `Database: jdbc:postgresql://localhost:10248/test (PostgreSQL 18.6)`. That's the container's
  random port and its `test` database, not the local `ledger` database on 5432.
- The alternative the same page describes, `@DynamicPropertySource`, does the same job by hand
  (registering `spring.datasource.url` and so on); `@ServiceConnection` removes that boilerplate.

---

## Agent Review

**Task given to the agent:** "Add a GET /payments/{id} endpoint that returns a single recorded
payment by its id. If no payment exists with that id, the endpoint should return 404."
The agent was given `PaymentEntity`, `PaymentRepository`, `SettlementService` and
`PaymentController` (comments removed), but not `ProblemHandler` or the DTO records.

**What it produced:** `SettlementService.findPayment(String id)` returning
`Optional<PaymentEntity>`, and a controller method that maps the Optional to
`ResponseEntity.ok(...)` or `ResponseEntity.notFound().build()`. Committed unchanged as
`5c58f52` on branch `agent/get-payment-by-id` before review.

**How it was checked:** each defect below was checked by reading the diff **and** by calling the
running app (2026-10-22, port 8081).

| # | Defect checked | Found? | Evidence | Fix |
|---|---|---|---|---|
| 1 | Field injection with `@Autowired` instead of a constructor parameter | **Not found** | No `@Autowired` anywhere; it reused the existing constructor-injected `SettlementService`. | None needed |
| 2 | JPA entity returned as the API type | **Not found** | Returns `ResponseEntity<PaymentResponse>` via `PaymentResponse::from`. The entity never leaves the service layer. | None needed |
| 3 | Missing `@Valid` or missing constraint annotations | **Found** | `@PathVariable String id` has no constraint. A 300-character junk id went straight to a database query and came back 404, though it can never be a valid id (ids are UUIDs). | Added `@Pattern(UUID, message = "must be a UUID")` on the path variable. Spring's method validation rejects bad ids before the service runs, and a new `HandlerMethodValidationException` handler in `ProblemHandler` returns a 400 `validation-error` problem with `errors.id = "must be a UUID"`. |
| 4 | Error body that is not a problem detail | **Found** | `ResponseEntity.notFound().build()` returned `HTTP 404` with **`Content-Length: 0`**: no body, no `application/problem+json`. That breaks the "same error shape on every failure" requirement. | The service now throws `PaymentNotFoundException`, and `ProblemHandler` maps it to a 404 `application/problem+json` with type `/problems/payment-not-found`, title, status, detail and instance. |

**Verified after the fixes:**
- `GET /payments/00000000-0000-0000-0000-000000000000` → `404`, `application/problem+json`,
  `"title":"Payment not found"`
- `GET /payments/does-not-exist` → `400`, `application/problem+json`, `"errors":{"id":"must be a UUID"}`
- New regression tests: `PaymentControllerWebTest.missingPaymentIs404ProblemDetailNotAnEmptyBody`,
  `nonUuidPaymentIdIs400ProblemDetailAndNeverReachesTheService`, `getPaymentByIdReturnsTheResponseRecord`,
  and `PaymentControllerIT.locationHeaderFromPostResolvesToTheSamePayment`. Full suite 17/17 green.

**Observations on the agent's reasoning:**
- It correctly noted that `/payments/settlement` still resolves to the settlement handler, because
  Spring prefers literal path segments over `/{id}`.
- It *predicted* defect 4 itself: it said a `PaymentNotFoundException` mapped by an existing
  `@RestControllerAdvice` "would match how `UnknownMerchantException` is probably handled", but
  chose an Optional and an empty 404 "because it needs no assumptions about code I can't see".
  That's a reasonable instinct, but it traded the API's error contract for local safety. The lesson
  for prompting: state the error contract in the prompt rather than relying on the agent to infer it.

**Bonus finding (in our code, not the agent's):** the new Location round-trip IT showed that the
`POST` response returned `recordedAt` with nanosecond precision (`…51.324929900Z`), while PostgreSQL
stored and returned microseconds rounded (`…51.324930Z`). The POST was echoing a timestamp that was
never stored. Fixed by truncating `Instant.now()` to `ChronoUnit.MICROS` in
`SettlementService.recordPayment`.
