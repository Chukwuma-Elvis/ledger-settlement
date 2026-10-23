# Load testing the settlement service

| File | Purpose |
|---|---|
| `seed-load-merchant.sql` | Seeds merchant `MR-LOAD` with 500 deterministic payments (owed 2,611,698 minor units). Re-runnable. |
| `settlement-load.js` | k6: 200 virtual users, no think time, 60 s against `GET /payments/settlement?merchantId=MR-LOAD`; every response is checked for the correct `owedMinor`. |
| `reference-data-stub/ReferenceDataStub.java` | Stand-in for the reference-data service: `GET /currencies` on port 8091 returns the currency table as CSV after a 2 s delay. |

## Run

```bash
psql -h localhost -U ledger -d ledger -f perf/seed-load-merchant.sql
java perf/reference-data-stub/ReferenceDataStub.java 8091 2000      # separate terminal, leave running
mvn -B package -DskipTests && java -jar target/ledger-settlement.jar  # wait for "Started LedgerApplication"
k6 run -e DURATION=15s perf/settlement-load.js                        # warm runs only: 15 s warm-up, discarded
k6 run perf/settlement-load.js                                        # measured run
```

## Rules for comparable runs

- Fresh JVM for every run; for cold-start measurements, no warm-up run.
- Same script, same seed data, same machine; change exactly one thing between compared runs.
- Close other heavy applications; repeat each configuration at least 3 times, interleaved
  (A B A B A B), and state the run-to-run spread.
- On startup the service sends one warm-up request through the request path
  (`StartupWarmUp`, disable with `ledger.warm-up.enabled=false`) so the first burst of load
  doesn't pay for class loading and query compilation.
