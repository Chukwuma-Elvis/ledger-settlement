# JMH benchmarks: the settlement service's own code

Lab 3 write-up for the Ledger settlement service. Branch `feature/jmh-benchmarks` (from
`feature/gc-tuning`, so the code measured is the service after the Lab 2 allocation fix).
Measured 2026-10-22 on JDK 25.0.4+7-LTS-189 (HotSpot), JMH 1.37, Intel Core i5-8350U
(8 logical CPUs), 11.8 GB RAM, Windows 11 Pro. `benchmarks/results.json` records the JDK, the VM
and, through `-jvmArgsAppend -Dbench.machine=…`, the machine.

**Short answer:** none of the three methods is worth optimising. The settlement calculation takes
**0.837 ± 0.004 µs** for 500 payments and allocates **248 B**; the request it serves has a p99 of
about 3.2 ms (3.38 and 3.02 ms in the two tuned runs, docs/gc.md section 8). The dead-code-elimination error, by contrast, is large: the broken
mapping benchmark reports a number **14× too small** and allocates nothing because the JIT deleted
the work.

## 1. What is measured, and why

All three are real methods from the `service` module; `benchmarks` depends on the service's plain
jar (`service/pom.xml` repackages with the `exec` classifier), so nothing is copied. No Spring
context is started: each object is built by hand in `@Setup`.

| Benchmark | Service method | Why this method |
|---|---|---|
| `mapping_returned`, `mapping_blackhole` (and the broken one) | `PaymentResponse.from(PaymentEntity)` | The entity→API mapping. It runs on **every** `POST /payments` and `GET /payments/{id}`, the two endpoints that are not settlement, so it is the per-request mapping cost of the service. |
| `validationProblemDetail` | `ProblemHandler.handleValidation(…)` | The string- and collection-heavy path: it sorts field errors into a `TreeMap`, builds the detail with string concatenation and `Collectors.joining`, and builds a `ProblemDetail`. It runs on every rejected payment, which is the cost a misbehaving client imposes on us. |
| `settlement500Payments` | `SettlementService.settlementFor("MR-LOAD")` | The core business calculation and the endpoint Lab 2 put under load (300 requests/s). It sums `long` minor units with `Math.addExact` and computes the fee in `BigDecimal` (never `double`, which cannot hold 0.031 exactly). Only the repository is stubbed, so the measured code is exactly what runs after the query returns. |

Lab 2's allocation profile put the hot site inside Hibernate's row loading (timestamp conversion and
the persistence context), not in our own code. That path needs a database, so it cannot be a
microbenchmark. What this suite can say is how much of the remaining allocation is ours: see
section 5.

## 2. Inputs: `@State` and `@Setup`

Every input lives in `LedgerBenchmarks.Inputs`, `@State(Scope.Benchmark)`, built in a `@Setup`
method (once per fork, not timed). No benchmark method contains a literal or a constant; the
merchant id `"MR-LOAD"` passed to `settlementFor` is only used as the error message key, the
payments come from the state object.

- **Settlement:** 500 `PaymentAmount` records with the **same shape as the service's seed data**
  (`perf/seed-load-merchant.sql`): GBP, amounts `1000 + (i × 37) mod 9000` minor units for
  i = 1…500. The real `SettlementService` with the production fee rate (0.031) and a real
  `CurrencyTable`; the `PaymentRepository` is a `java.lang.reflect.Proxy` returning that list.
- **Mapping:** a `PaymentEntity` with a random UUID id and `Instant.now()`, created at run time.
- **Validation:** a `MethodArgumentNotValidException` for a `POST /payments` with a blank
  `merchantId` and a negative amount (the two field errors Spring MVC produces for that body), on
  the real `PaymentController.recordPayment` parameter, and a `MockHttpServletRequest` for
  `POST /payments`.

## 3. Run configuration

Class-level annotations on `LedgerBenchmarks`:

```java
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 10)
@Fork(3)
```

Iterations are JMH's default 10 s, so each benchmark gets 3 × (5 + 10) × 10 s = 7.5 minutes.

**Why forks matter:** each fork is a fresh JVM, so one run's JIT compilation decisions, profile
pollution and heap layout cannot skew the others, and fork-to-fork variation ends up in the error
instead of hiding.

**Is the warm-up long enough?** Yes. The five warm-up iterations of fork 1 (final run,
`run2-console.txt`) against the first measured ones, in µs/op:

| Benchmark | Warm-up 1–5 | Measured 1–3 |
|---|---|---|
| mapping_returned | 0.010, 0.011, 0.009, 0.009, 0.009 | 0.009, 0.009, 0.009 |
| mapping_blackhole | 0.011, 0.011, 0.009, 0.009, 0.009 | 0.009, 0.009, 0.009 |
| validationProblemDetail | 0.473, 0.495, 0.500, 0.496, 0.498 | 0.494, 0.492, 0.494 |
| settlement500Payments | 0.866, 0.862, 0.838, 1.066, 0.867 | 0.841, 0.833, 0.844 |

Every benchmark is at its measured level by warm-up iteration 3. Settlement's 1.066 in warm-up 4 is
a single outlier after it had already settled (forks 2 and 3 show 0.845/0.838 and 0.832 there),
and the 30 measured iterations range 0.827–0.856 µs/op, so it did not reach the measurement.
Nothing was reduced: all runs used 5 warm-up, 10 measurement and 3 forks, except the two
diagnostic runs in section 8, which say so.

## 4. Results

Final run: `benchmarks/results.json`. Error is JMH's **99.9% confidence interval** half-width
(Student's t over the 30 measured iterations, 3 forks × 10); the interval column is
`score ± error`. Bytes per operation come from the separate `-prof gc` run
(`benchmarks/results-prof-gc.json`), because a profiler run is not the run to quote times from.

| Benchmark | Score ± error (µs/op) | 99.9% interval (µs/op) | Same, in ns/op | Alloc (B/op, `gc.alloc.rate.norm`) |
|---|---|---|---|---|
| `mappingBroken_resultDiscarded` ⚠️ broken | 0.000639 ± 0.000024 | [0.000615, 0.000663] | 0.64 ± 0.02 | **≈ 0** (JMH prints ≈ 10⁻⁶) |
| `mapping_returned` | 0.008945 ± 0.000099 | [0.008846, 0.009044] | 8.95 ± 0.10 | 40.0 ± 0.0 |
| `mapping_blackhole` | 0.008863 ± 0.000080 | [0.008783, 0.008943] | 8.86 ± 0.08 | 40.0 ± 0.0 |
| `validationProblemDetail` | 0.494170 ± 0.007099 | [0.487071, 0.501269] | 494.2 ± 7.1 | 1,405.3 ± 20.5 |
| `settlement500Payments` | 0.837486 ± 0.004251 | [0.833235, 0.841737] | 837.5 ± 4.3 | 248.0 ± 0.0 |

The JMH console rounds to three decimals (for example `0.009 ± 0.001 us/op`); the table uses the
unrounded values from `results.json`.

### Comparisons

| Comparison | Intervals (µs/op) | Verdict |
|---|---|---|
| Broken vs fixed (returned) | [0.000615, 0.000663] vs [0.008846, 0.009044] | **Different.** The broken benchmark reports 14.0× less time (13.3× to 14.7× across the interval ends). |
| Fixed by return vs fixed by Blackhole | [0.008846, 0.009044] vs [0.008783, 0.008943] | **Inconclusive: the intervals overlap.** The 0.08 ns gap is inside the error; the two fixes cost the same. |
| Final run vs `-prof gc` run, per benchmark | see section 7 | Four of five agree; one does not, and section 7 says why that is not a finding. |

No claim in this document rests on a difference smaller than the error. The settlement and
validation benchmarks measure different methods, so their 1.7× difference is not compared as if one
were a faster version of the other.

## 5. Allocation, traced to lines

`gc.alloc.rate.norm` says how many bytes; a short JFR run (`-prof jfr`, section 8) says where. The
allocation samples were attributed to the first frame in the service's own code
(`jfr-alloc-views.txt` in the evidence zip). Percentages are of sampled allocation weight, so they
are approximate.

**`settlementFor`, 248 B/op.**
- ~88%: **line 56**, `payments.stream().mapToLong(PaymentAmount::amountMinor).reduce(0L, Math::addExact)`.
  The stream machinery, not the data: `AbstractList$RandomAccessSpliterator` (52.6%),
  `ReferencePipeline$Head` (11.7%), the `mapToLong` stage (12.0%) and the reducing sink (11.5%).
  Those objects are per call, independent of the 500 payments; the sum itself allocates nothing.
- ~12%: **line 61**, the `Settlement` record returned to the caller.
- The `BigDecimal` fee calculation (`feeFor`) does not appear in the samples: its intermediates
  never leave the method, so the JIT most likely removed them with escape analysis.
- A plain `for` loop over the list would remove the stream objects, roughly 200 of the 248 bytes.
  Not worth doing: at 300 requests/s it is about 60 KB/s against the 49.8 MB/s the tuned service
  allocates (docs/gc.md). This settlement code is **0.15%** of the ≈ 166 KB allocated per request
  (49.8 MB/s ÷ 300/s); the rest comes from outside `settlementFor`.

**`handleValidation`, 1,405 B/op.**
- ~52%: **line 42**, `error.getKey() + ": " + error.getValue()`: the `byte[]` behind each
  concatenated string (`StringConcatHelper.newArrayWithSuffix`), one per field error.
- ~16%: **line 43**, `Collectors.joining("; ")`: its `StringJoiner` (7.4%) and the joined
  string's `byte[]` (8.9%). These two samples' 8-frame stacks stop inside the JDK before reaching
  `ProblemHandler`; `joining` on line 43 is the only `StringJoiner` and the only second
  concatenation in the method.
- 12.0% line 39 (stream over the field errors), 9.6% line 41 (`TreeMap.entrySet()`), 7.1% line 46
  (`ProblemDetail.setProperty` creates its `LinkedHashMap` of properties), 0.6% line 101
  (`URI.create` for the instance).

**`PaymentResponse.from`, 40 B/op:** exactly one `PaymentResponse` record (12-byte header, four
compressed references, one `long`, padded to 40). The broken version allocates **0 B/op**: see the
next section.

## 6. The dead-code-elimination error

```java
/** DELIBERATELY BROKEN (dead-code elimination demo, kept so the error can be reproduced) */
@Benchmark
public void mappingBroken_resultDiscarded(Inputs in) {
    PaymentResponse.from(in.payment);
}
```

- **Broken:** 0.000639 ± 0.000024 µs/op (0.64 ns), **0 B/op**.
- **Fixed:** 0.008945 ± 0.000099 µs/op (8.95 ns), **40 B/op**.
- **Size of the error: 14.0×.** The broken benchmark's 0.64 ns is roughly two clock cycles at this
  CPU's turbo frequency: JMH's own loop and nothing else.

**What the JIT removed.** C2 inlines `from` and the five getters. The new `PaymentResponse` is never
stored, returned or passed anywhere, so escape analysis proves it does not escape and replaces it
with its five fields held in registers; nothing reads those fields, so the field loads from the
entity are dead and are deleted too. What is left is an empty loop body. The **0 B/op** is the
evidence: the allocation, the one thing `from` must do, is gone. The returned version keeps the
40-byte object because JMH consumes it.

**The two fixes, and when to use each.**
- **Return the value** (`mapping_returned`): the default when a benchmark produces one result. JMH
  passes the return value to its own Blackhole, so the JIT cannot prove it unused.
- **`Blackhole.consume`** (`mapping_blackhole`): for a benchmark that produces several values, or
  that computes a value inside a loop, where only one could be returned.

They measure the same (comparison above: inconclusive, 8.95 vs 8.86 ns). On this JDK JMH uses
*compiler blackholes* (the console says so), which make `consume` close to free, so neither fix adds
measurable overhead.

## 7. Run-to-run, and where an earlier conclusion was noise

**Smoke run vs final run.** Before the full suite, a 13-second smoke run checked that every
benchmark worked: one fork, one 1-second warm-up, one 1-second iteration (`run0-smoke-console.txt`).
It reported:

| Benchmark | Smoke run (µs/op, no error bar possible) | Final run (µs/op) |
|---|---|---|
| mapping_blackhole | 0.012 | 0.008863 ± 0.000080 |
| mapping_returned | 0.013 | 0.008945 ± 0.000099 |
| settlement500Payments | 1.100 | 0.837486 ± 0.004251 |
| validationProblemDetail | 0.578 | 0.494170 ± 0.007099 |

Taken at face value the smoke run said two things: that `Blackhole` was about 8% cheaper than
returning (0.012 vs 0.013), and that settlement cost about 1.1 µs. **Both were noise.** The full run
shows the two fixes overlap (no difference), and settlement is 24% cheaper than the smoke run said:
1 second was not enough for C2 to finish compiling, and one iteration has no error bar to warn
about it.

**Final run vs `-prof gc` run.** The suite ran twice with the full configuration, the first time
with `-prof gc`:

| Benchmark | Final (µs/op) | `-prof gc` run (µs/op) | Intervals |
|---|---|---|---|
| mappingBroken_resultDiscarded | 0.000639 ± 0.000024 | 0.000723 ± 0.000061 | overlap |
| mapping_returned | 0.008945 ± 0.000099 | 0.008902 ± 0.000096 | overlap |
| mapping_blackhole | 0.008863 ± 0.000080 | 0.008936 ± 0.000113 | overlap |
| settlement500Payments | 0.837486 ± 0.004251 | 0.843183 ± 0.014282 | overlap |
| validationProblemDetail | 0.494170 ± 0.007099 | 0.527310 ± 0.011959 | **no overlap** (+6.7%) |

Four of five reproduce. Validation does not: the two intervals miss each other by 0.014 µs. The
runs differ in one setting (the GC profiler was on), so this is not evidence of anything about the
code. It is evidence that a single run's 99.9% interval for this benchmark is narrower than its
run-to-run spread: validation allocates the most (1.4 KB/op, 2,619 young collections in the profiled
run), so it is the most exposed to GC timing and background load. **A change of less than ~7% to
`handleValidation` should not be believed from one run;** repeat the run.

## 8. Commands (exact)

Build (repository root): `mvn -B -q package -DskipTests`. Then from `benchmarks/`
(`commands.log` in the evidence zip has the timestamps):

```bash
# run 1: allocation profile -> results-prof-gc.json            (22:26:35, 37 min 41 s)
java -jar target/benchmarks.jar -prof gc -rf json -rff results-prof-gc.json -jvmArgsAppend -Dbench.machine=Intel-i5-8350U_8-logical-CPUs_11.8GB-RAM_Windows-11-Pro
# run 2: FINAL run -> results.json                              (23:04:17, 37 min 40 s)
java -jar target/benchmarks.jar -rf json -rff results.json -jvmArgsAppend -Dbench.machine=Intel-i5-8350U_8-logical-CPUs_11.8GB-RAM_Windows-11-Pro
```

These are the lab's commands plus `-jvmArgsAppend`, which only puts the machine description into
the JSON. Diagnostic runs, **reduced on purpose and not used for any time in the tables**:

```bash
# smoke test (22:25, repository root): 1 fork, 1 x 1 s warm-up, 1 x 1 s measurement
java -jar benchmarks/target/benchmarks.jar -f 1 -wi 1 -i 1 -w 1s -r 1s
# allocation sites (23:43, repository root): 1 fork, 3 warm-up, 3 measurement, JFR attached
java -jar benchmarks/target/benchmarks.jar 'validationProblemDetail|settlement500Payments' -f 1 -wi 3 -i 3 -prof "jfr:dir=jfr-alloc;stackDepth=8"
```

Conditions: a background CPU monitor sampled every 10 s (`background-cpu.csv`, 445 samples):
mean **4.3%** of the machine used outside the benchmark JVM, maximum 31.2%. The busiest other
process was Task Manager in 389 samples; about every 10 minutes a WebView2 host process
(`msedgewebview2`) took up to 24% for one or two samples. The Windows power plan was **Balanced**, not High performance.

Evidence: `docs/evidence/lab3-evidence.zip` holds every console log, both JSON files, the commands
log, the CPU monitor output, the two JFR recordings and the allocation-site views.

## 9. Limits: what this suite does not tell you

1. **Concurrency.** Every benchmark runs on one thread. It says nothing about contention on the
   `CurrencyTable` map, the connection pool or the virtual-thread scheduler (Lab 1's territory).
   *Would address it:* the same benchmarks with `@Threads(8)` and `@Threads(200)`, and the k6 load
   test at rising arrival rates with p99 per rate.
2. **Production data sizes and caches.** One merchant's 500 payments (500 small records, about
   12 KB) and one set of inputs fit in the CPU's L1/L2 cache and are read again on every call. A merchant with
   500,000 payments, or many merchants interleaved, would miss cache and could scale non-linearly.
   *Would address it:* `@Param({"500", "50000", "500000"})` on the payment count, plus a state
   holding many merchants chosen at random per invocation.
3. **The harness is not the request path.** There is no HTTP, no Spring proxy or transaction, no
   JDBC or Hibernate, and no Jackson serialisation. Settlement's 0.84 µs is about 0.03% of the
   endpoint's ≈ 3.2 ms p99 (docs/gc.md section 8); the cost that matters is in what was stubbed. The JIT
   profile is also cleaner than in production's mixed workload, where the same methods may be
   compiled differently. *Would address it:* a JFR CPU profile of the service under the Lab 2 k6
   load, to see which share of request time each method really takes.
4. **Bytes per operation is not GC behaviour.** 1.4 KB/op says nothing about pause times, promotion
   or heap sizing under load. *Would address it:* the Lab 2 method: a ten-minute recording under
   steady load, reading collections and pauses.
5. **One machine, one JDK, one run per configuration.** A laptop CPU with turbo and a Balanced power
   plan, Windows, JDK 25.0.4 with compiler blackholes. Section 7 shows a single run can be off by
   ~7%. *Would address it:* repeating the final run several times, interleaved, on a
   High-performance power plan, and on a Linux CI runner, then comparing the spread across runs.

## 10. Reproduce

From a clean clone, at the repository root (one command, also in README.md):

```bash
mvn -B -q package -DskipTests && java -jar benchmarks/target/benchmarks.jar -rf json -rff benchmarks/results.json
```

About 38 minutes for the suite (measured 37 min 40 s) plus about a minute to build. Add `-prof gc`
for bytes per operation.
