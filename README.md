# Ledger settlement service

Records payments (`POST /payments`) and reports what a merchant is owed
(`GET /payments/settlement?merchantId=…`). Spring Boot 4.1.1, Java 25, PostgreSQL.

| Module | What |
|---|---|
| `service/` | The application. Runnable jar: `service/target/ledger-settlement-exec.jar`. |
| `benchmarks/` | JMH microbenchmarks of the service's own classes. Executable jar: `benchmarks/target/benchmarks.jar`. The service jar carries no JMH dependency. |
| `perf/` | k6 load tests, seed data and the reference-data stub ([perf/README.md](perf/README.md)). |
| `docs/` | Lab write-ups (`gc.md`, `benchmarks.md`; `threads.md` arrives with the Lab 1 docs PR), flight recordings and zipped raw evidence. |

## Build and test

```bash
mvn -B verify          # all modules; unit tests plus Testcontainers ITs (needs Docker)
```

## Run the benchmark suite: one command

From the repository root, on a fresh clone:

```bash
mvn -B -q package -DskipTests && java -jar benchmarks/target/benchmarks.jar -rf json -rff benchmarks/results.json
```

This builds every module and runs all benchmarks with the configuration in the code
(`@Warmup(iterations = 5)`, `@Measurement(iterations = 10)`, `@Fork(3)`, JMH's default 10 s per
iteration). A full run takes **about 38 minutes** (measured 37 min 40 s) on the reference machine
(8 logical CPUs); the build adds about a minute. The final run's raw output is
`benchmarks/results.json`. Add `-prof gc` for bytes allocated per operation. Results and the
analysis are in [docs/benchmarks.md](docs/benchmarks.md).

## Run the service

```bash
java perf/reference-data-stub/ReferenceDataStub.java 8091 2000   # the reference-data service; leave running
java -jar service/target/ledger-settlement-exec.jar
```

Needs PostgreSQL with a `ledger` database; credentials come from environment variables or a
git-ignored `.env` (see `.env.example`).
