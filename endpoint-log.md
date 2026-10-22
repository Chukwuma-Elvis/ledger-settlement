# Endpoint Log

The five required calls, made against the running service, in this order.

| Request | Status | Body summary | Time |
|---|---|---|---|
| `POST /payments` `{"merchantId":"MR-4471","amountMinor":128450,"currency":"GBP"}` | 201 Created | Payment echoed back with generated UUID `id` and `recordedAt`; `Location: /payments/19fe0d18-f386-45f1-94a5-db0ab248bd8a` | 492 ms |
| `POST /payments` `{"merchantId":"MR-4471","amountMinor":-128450,"currency":"GBP"}` | 400 Bad Request | `application/problem+json`, type `/problems/validation-error`, detail `amountMinor: must be greater than 0` | 77 ms |
| `POST /payments` `{"merchantId":"","amountMinor":128450,"currency":"GBP"}` | 400 Bad Request | `application/problem+json`, type `/problems/validation-error`, detail `merchantId: must not be blank` | 10 ms |
| `GET /payments/settlement?merchantId=MR-4471` | 200 OK | `totalMinor 128450`, `feeMinor 3981`, **`owedMinor 124469`**, `currency GBP` | 657 ms |
| `GET /payments/settlement?merchantId=MR-0000` | 404 Not Found | `application/problem+json`, type `/problems/unknown-merchant`, detail `No payments recorded for merchant MR-0000` | 9 ms |

## How this was recorded

- **When:** 2026-10-22 at 14:36 (UTC+01:00)
- **Build:** `main` at commit `5d68858`, run as `java -jar target/ledger-settlement.jar` on port 8080
- **Database:** local PostgreSQL 18.6, `ledger` database, `payments` table empty before call 1
- **Time column:** total round-trip time reported by curl (`-w "%{time_total}"`), rounded to the millisecond
- **Client:** `curl` from the same machine (localhost, so no network latency)

## Reading the times

Calls 1 and 4 are the slowest because each was the **first** request of its kind on a freshly
started JVM. Call 1 paid for the first database write (Hikari connection, Hibernate insert, JIT
warm-up) and call 4 for the first read query. The error calls (2, 3, 5) are fast because
validation rejects calls 2 and 3 before any database work, and call 5 runs an already-warm query
that returns no rows. These are single cold-start samples, not a benchmark. Module 2's load tests
and JMH lab are where performance gets measured properly.

## Reproduce

Start the app on 8080 against an empty `payments` table, then run (Git Bash; in PowerShell use
`curl.exe` and escape the inner quotes as `\"`):

```bash
curl -i -w '\n%{time_total}s\n' -X POST http://localhost:8080/payments -H 'Content-Type: application/json' -d '{"merchantId":"MR-4471","amountMinor":128450,"currency":"GBP"}'
curl -i -w '\n%{time_total}s\n' -X POST http://localhost:8080/payments -H 'Content-Type: application/json' -d '{"merchantId":"MR-4471","amountMinor":-128450,"currency":"GBP"}'
curl -i -w '\n%{time_total}s\n' -X POST http://localhost:8080/payments -H 'Content-Type: application/json' -d '{"merchantId":"","amountMinor":128450,"currency":"GBP"}'
curl -i -w '\n%{time_total}s\n' "http://localhost:8080/payments/settlement?merchantId=MR-4471"
curl -i -w '\n%{time_total}s\n' "http://localhost:8080/payments/settlement?merchantId=MR-0000"
```
