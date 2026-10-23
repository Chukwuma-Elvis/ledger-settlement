# JMH benchmarks: the settlement service's own code

Lab 3 write-up for the Ledger settlement service. Branch `feature/jmh-benchmarks` (from
`feature/gc-tuning`, so the code measured is the service after the Lab 2 allocation fix).
Measured 2026-10-22/23 on JDK 25.0.4+7-LTS-189 (HotSpot), JMH 1.37, Intel Core i5-8350U
(8 logical CPUs), 11.8 GB RAM, Windows 11 Pro. `benchmarks/results.json` records the JDK, the VM
and, through `-jvmArgsAppend -Dbench.machine=…`, the machine.

**Short answer:** none of the three methods is worth optimising. The settlement calculation takes
**0.838 ± 0.004 µs** for 500 payments and allocates **248 B**; the request it serves has a p99 of
about 3.2 ms (3.38 and 3.02 ms in the two tuned runs, docs/gc.md section 8). The
dead-code-elimination error, by contrast, is large: the broken mapping benchmark reports a time
**about 13× too small** and allocates nothing, because the JIT deleted the work.

## 1. What is measured, and why

All three are real methods from the `service` module. `benchmarks` depends on the service's plain
jar (`service/pom.xml` repackages with the `exec` classifier), so nothing is copied. No Spring
context is started: each object is built by hand in `@Setup`.

| Benchmark | Service method | Why this method |
|---|---|---|
| `mapping_returned`, `mapping_blackhole` (and the broken one) | `PaymentResponse.from(PaymentEntity)` | The entity→API mapping. It runs on **every** `POST /payments` and `GET /payments/{id}`, the two endpoints that are not settlement, so it is the service's per-request mapping cost. |
| `validationProblemDetail` | `ProblemHandler.handleValidation(…)` | The string- and collection-heavy path: it sorts field errors into a `TreeMap`, builds the detail with string concatenation and `Collectors.joining`, and builds a `ProblemDetail`. It runs on every rejected payment, so it is the cost a misbehaving client imposes on us. |
| `settlement500Payments` | `SettlementService.settlementFor(merchantId)` | The core business calculation and the endpoint Lab 2 put under load (300 requests/s). It sums `long` minor units with `Math.addExact` and computes the fee in `BigDecimal` (never `double`, which cannot hold 0.031 exactly). Only the repository is stubbed, so the measured code is exactly what runs after the query returns. |

Lab 2's allocation profile put the hot site inside Hibernate's row loading (timestamp conversion and
the persistence context), not in our own code. That path needs a database, so it cannot be a
microbenchmark. What this suite can say is how much of the remaining allocation is ours (section 5).

## 2. Inputs: `@State` and `@Setup`

Every input lives in `LedgerBenchmarks.Inputs`, `@State(Scope.Benchmark)`, built in a `@Setup`
method (once per fork, not timed). No measured method contains a literal or a constant; each reads
its inputs from the state object.

- **Settlement:** the merchant id, and 500 `PaymentAmount` records with the **same shape as the
  service's seed data** (`perf/seed-load-merchant.sql`): GBP, amounts `1000 + (i × 37) mod 9000`
  minor units for i = 1…500. The real `SettlementService` with the production fee rate (0.031) and a
  real `CurrencyTable`; the `PaymentRepository` is a `java.lang.reflect.Proxy` returning that list.
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
instead of hiding. This suite shows it: `validationProblemDetail`'s three forks averaged
0.4765, 0.4954 and 0.4884 µs/op in the final run, a 4% spread between JVMs that a single fork
would have hidden.

**Is the warm-up long enough?** Yes. Warm-up then measured iterations, fork 1 of the final run
(`run-final-console.txt`), µs/op:

| Benchmark | Warm-up 1–5 | Measured 1–4 |
|---|---|---|
| mapping_returned | 0.010, 0.011, 0.009, 0.010, 0.009 | 0.009, 0.009, 0.009, 0.009 |
| mapping_blackhole | 0.010, 0.011, 0.009, 0.009, 0.009 | 0.009, 0.009, 0.009, 0.009 |
| validationProblemDetail | 0.472, 0.483, 0.474, 0.494, 0.478 | 0.474, 0.478, 0.476, 0.477 |
| settlement500Payments | 0.859, 0.838, 0.842, **1.061**, 0.851 | 0.832, 0.842, 0.836, 0.829 |

Every benchmark is at its measured level by warm-up iteration 3. Two single slow warm-up
iterations appear after that: settlement's 1.061 in fork 1 (it happened in fork 1 of the earlier
run too, 1.066), and validation's 0.608 in fork 2 iteration 3. Both were back to normal by the next
iteration, and no measured iteration is near them (settlement's 30 range 0.829–0.859, validation's
0.474–0.510). Nothing was reduced: every full run used 5 warm-up, 10 measurement and 3 forks; the
two diagnostic runs in section 8 are reduced and say so.

## 4. Results

**Final run: `benchmarks/results.json`**, produced from a clean clone of this branch (commit
`8632d94`) by the README command. Error is JMH's **99.9% confidence interval** half-width (Student's
t over the 30 measured iterations, 3 forks × 10); the interval column is `score ± error`. Bytes per
operation come from the `-prof gc` run made straight after it on the same build
(`benchmarks/results-prof-gc.json`); a profiler run is not the run to quote times from (section 7
shows why).

| Benchmark | Score ± error (µs/op) | 99.9% interval (µs/op) | Same, in ns/op | Alloc (B/op, `gc.alloc.rate.norm`) |
|---|---|---|---|---|
| `mappingBroken_resultDiscarded` ⚠️ broken | 0.000698 ± 0.000062 | [0.000636, 0.000761] | 0.70 ± 0.06 | **≈ 0** (JMH prints ≈ 10⁻⁶) |
| `mapping_returned` | 0.008864 ± 0.000056 | [0.008808, 0.008920] | 8.86 ± 0.06 | 40.0 ± 0.0 |
| `mapping_blackhole` | 0.008938 ± 0.000099 | [0.008839, 0.009037] | 8.94 ± 0.10 | 40.0 ± 0.0 |
| `validationProblemDetail` | 0.486747 ± 0.006377 | [0.480370, 0.493124] | 486.7 ± 6.4 | 1,373.3 ± 20.5 |
| `settlement500Payments` | 0.837741 ± 0.004012 | [0.833729, 0.841753] | 837.7 ± 4.0 | 248.0 ± 0.0 |

The JMH console rounds to three decimals (for example `0.009 ± 0.001 us/op`); the table uses the
unrounded values from the JSON files.

### Comparisons

| Comparison | Intervals | Verdict |
|---|---|---|
| Broken vs fixed (returned) | [0.000636, 0.000761] vs [0.008808, 0.008920] µs/op | **Different.** The broken benchmark reports **12.7×** less time (11.6× to 14.0× across the interval ends). |
| Fixed by return vs fixed by Blackhole | [0.008808, 0.008920] vs [0.008839, 0.009037] µs/op | **Inconclusive: the intervals overlap.** The two fixes cost the same. (Same verdict in the earlier run, where the two scores were in the opposite order.) |
| Validation allocation, earlier vs final `-prof gc` run | [1384.8, 1425.8] vs [1352.8, 1393.8] B/op | **Inconclusive: overlap.** The same code allocates the same; 1,405 vs 1,373 is within the error. |
| Each benchmark across the four full runs | section 7 | Agree, except where the GC profiler was on, or below one CPU cycle |

No claim in this document rests on a difference smaller than the error. The settlement and
validation benchmarks measure different methods, so their 1.7× difference is not read as one being a
faster version of the other.

## 5. Allocation, traced to lines

`gc.alloc.rate.norm` says how many bytes; a short JFR run (`-prof jfr`, section 8) says where. The
allocation samples were attributed to the first frame in the service's own code
(`jfr-alloc-views.txt` in the evidence zip). Percentages are of sampled allocation weight, so they
are approximate. That run used commit `706205a`, which differs from the final code only in where the
benchmark reads the merchant id from; settlement allocates 248.0 B/op in both.

**`settlementFor`, 248 B/op.**
- ~88%: **line 56**, `payments.stream().mapToLong(PaymentAmount::amountMinor).reduce(0L, Math::addExact)`.
  The stream machinery, not the data: `AbstractList$RandomAccessSpliterator` (52.6%),
  `ReferencePipeline$Head` (11.7%), the `mapToLong` stage (12.0%) and the reducing sink (11.5%).
  Those objects are made once per call, whatever the number of payments; the sum itself allocates
  nothing.
- ~12%: **line 61**, the `Settlement` record returned to the caller.
- The `BigDecimal` fee calculation (`feeFor`) does not appear in the samples: its intermediates
  never leave the method, so the JIT most likely removed them with escape analysis.
- A plain `for` loop over the list would remove the stream objects, roughly 200 of the 248 bytes.
  Not worth doing: at 300 requests/s it is about 60 KB/s against the 49.8 MB/s the tuned service
  allocates (docs/gc.md). This code is **0.15%** of the ≈ 166 KB allocated per settlement request
  (49.8 MB/s ÷ 300/s); the rest is allocated outside `settlementFor`.

**`handleValidation`, about 1.4 KB/op.**
- ~52%: **line 42**, `error.getKey() + ": " + error.getValue()`: the `byte[]` behind each
  concatenated string (`StringConcatHelper.newArrayWithSuffix`), one per field error.
- ~16%: **line 43**, `Collectors.joining("; ")`: its `StringJoiner` (7.4%) and the joined
  string's `byte[]` (8.9%). These two samples' 8-frame stacks stop inside the JDK before reaching
  `ProblemHandler`; `joining` on line 43 is the only `StringJoiner` and the only other
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

| | Broken | Fixed (returned) | Error |
|---|---|---|---|
| Final run | 0.000698 ± 0.000062 µs/op, 0 B/op | 0.008864 ± 0.000056 µs/op, 40 B/op | **12.7×** |
| Earlier run (`706205a`) | 0.000639 ± 0.000024 µs/op, 0 B/op | 0.008945 ± 0.000099 µs/op, 40 B/op | 14.0× |

The fixed time is the same in both runs; the ratio moves because the broken score is a fraction of a
nanosecond, where a few hundredths of a nanosecond change it by 10%. The broken benchmark's 0.6–0.7
ns is about two clock cycles at this CPU's turbo frequency: JMH's own loop and nothing else.

**What the JIT removed.** C2 inlines `from` and the five getters. The new `PaymentResponse` is never
stored, returned or passed anywhere, so escape analysis proves it does not escape and replaces it
with its five fields held in registers; nothing reads those fields, so the loads from the entity are
dead and are deleted too. What is left is an empty loop body. The **0 B/op** is the evidence: the
allocation, the one thing `from` must do, is gone. The returned version keeps the 40-byte object
because JMH consumes it.

**The two fixes, and when to use each.**
- **Return the value** (`mapping_returned`): the default when a benchmark produces one result. JMH
  passes the return value to its own Blackhole, so the JIT cannot prove it unused.
- **`Blackhole.consume`** (`mapping_blackhole`): for a benchmark that produces several values, or
  that computes values inside a loop, where only one could be returned.

They measure the same (inconclusive in both runs). On this JDK JMH uses *compiler blackholes* (the
console says so), which make `consume` close to free, so neither fix adds measurable overhead.

## 7. Run-to-run, and where my own earlier conclusions were noise

The full suite ran four times with the full configuration: twice on commit `706205a` (in the
working tree) and twice on `8632d94` (from a clean clone), each pair one plain run and one with
`-prof gc`.

| Benchmark | Earlier plain | Earlier `-prof gc` | **Final plain** | Final `-prof gc` |
|---|---|---|---|---|
| mappingBroken_resultDiscarded | 0.000639 ± 0.000024 | 0.000723 ± 0.000061 | **0.000698 ± 0.000062** | 0.000624 ± 0.000002 |
| mapping_returned | 0.008945 ± 0.000099 | 0.008902 ± 0.000096 | **0.008864 ± 0.000056** | 0.008931 ± 0.000140 |
| mapping_blackhole | 0.008863 ± 0.000080 | 0.008936 ± 0.000113 | **0.008938 ± 0.000099** | 0.008836 ± 0.000034 |
| settlement500Payments | 0.837486 ± 0.004251 | 0.843183 ± 0.014282 | **0.837741 ± 0.004012** | 0.840096 ± 0.006514 |
| validationProblemDetail | 0.494170 ± 0.007099 | 0.527310 ± 0.011959 | **0.486747 ± 0.006377** | 0.522927 ± 0.012639 |

(µs/op, score ± 99.9% error.)

- **Mapping and settlement:** all four runs overlap each other. Reproducible.
- **Validation:** the two plain runs overlap each other, and the two `-prof gc` runs overlap each
  other, but no plain run overlaps a profiled one. The profiler makes this benchmark about 7% slower
  (it is the one that allocates most, so it pays most for the profiler's bookkeeping). The
  plain-run numbers are the ones to use.
- **Broken:** the final `-prof gc` run (0.000624 ± 0.000002) does not overlap two of the others.
  The differences are below 0.1 ns, under one clock cycle, in a benchmark that measures nothing.
  They mean nothing.

**Earlier conclusions that turned out to be noise:**

1. **The smoke run.** Before the full suite, a 13-second smoke run checked that every benchmark
   worked: one fork, one 1-second warm-up, one 1-second iteration (`run0-smoke-console.txt`). It
   reported blackhole 0.012 vs returned 0.013 µs/op, settlement 1.100 and validation 0.578. Read at
   face value, that said `Blackhole` was about 8% cheaper than returning and settlement cost about
   1.1 µs. **Both were wrong:** the full runs show the two fixes are the same, and settlement is
   0.838 µs, 24% less. One second was not enough for C2 to finish compiling, and one iteration has
   no error bar to warn about it.
2. **My first reading of validation.** After the first two full runs, the plain and profiled
   validation results did not overlap, and I first wrote that one run's interval was narrower than
   the run-to-run spread, so a 7% change could not be trusted from one run. The second pair of runs
   shows that was wrong: plain runs agree with plain runs. The gap was the profiler, not noise.
   Times from a `-prof gc` run should not be compared with times from a plain run.

## 8. Commands (exact)

Final runs, from a fresh clone of `feature/jmh-benchmarks` at `8632d94` (`clean-clone-build.txt`
and `commands.log` in the evidence zip have the timestamps and the build log):

```bash
git clone --branch feature/jmh-benchmarks <repo> clean-clone-lab3 && cd clean-clone-lab3
mvn -B verify                       # 17 unit + 5 Testcontainers IT, BUILD SUCCESS; service jar has no JMH classes
# FINAL run -> results.json: the README command plus the machine tag      (2026-10-22 23:54, 37 min 40 s)
mvn -B -q package -DskipTests && java -jar benchmarks/target/benchmarks.jar -rf json -rff benchmarks/results.json -jvmArgsAppend -Dbench.machine=Intel-i5-8350U_8-logical-CPUs_11.8GB-RAM_Windows-11-Pro
# allocation run -> results-prof-gc.json                                  (2026-10-23 00:31, 37 min 41 s)
java -jar benchmarks/target/benchmarks.jar -prof gc -rf json -rff benchmarks/results-prof-gc.json -jvmArgsAppend -Dbench.machine=Intel-i5-8350U_8-logical-CPUs_11.8GB-RAM_Windows-11-Pro
```

`-jvmArgsAppend` only puts the machine description into the JSON. The two earlier full runs used
the lab's form of the same commands, from `benchmarks/` in the working tree at `706205a`
(`java -jar target/benchmarks.jar -prof gc -rf json -rff results-prof-gc.json …` at 22:26 and
`java -jar target/benchmarks.jar -rf json -rff results.json …` at 23:04); their output is in the
evidence zip under `earlier-runs/`.

Diagnostic runs, **reduced on purpose and not used for any time in the tables**:

```bash
# smoke test (22:25, repository root): 1 fork, 1 x 1 s warm-up, 1 x 1 s measurement
java -jar benchmarks/target/benchmarks.jar -f 1 -wi 1 -i 1 -w 1s -r 1s
# allocation sites (23:43, repository root): 1 fork, 3 warm-up, 3 measurement, JFR attached
java -jar benchmarks/target/benchmarks.jar 'validationProblemDetail|settlement500Payments' -f 1 -wi 3 -i 3 -prof "jfr:dir=jfr-alloc;stackDepth=8"
```

Conditions: a background CPU monitor sampled every 10 s during all four full runs
(`background-cpu.csv`, 454 samples for the final pair): mean **4.3%** of the machine used outside
the benchmark JVM, maximum 28.3%. The busiest other process was Task Manager in most samples; about
every 10 minutes a WebView2 host process (`msedgewebview2`) took up to 24% for one or two samples.
The Windows power plan was **Balanced**, not High performance.

Evidence: `docs/evidence/lab3-evidence.zip` holds every console log, all JSON files, the commands
logs, the clean-clone build log, the CPU monitor output, the two JFR recordings and the
allocation-site views.

## 9. Limits: what this suite does not tell you

1. **Concurrency.** Every benchmark runs on one thread. It says nothing about contention on the
   `CurrencyTable` map, the connection pool or the virtual-thread scheduler (Lab 1's territory).
   *Would address it:* the same benchmarks with `@Threads(8)` and `@Threads(200)`, and the k6 load
   test at rising arrival rates with p99 per rate.
2. **Production data sizes and caches.** One merchant's 500 payments (500 small records, about
   12 KB) and one set of inputs fit in the CPU's L1/L2 cache and are read again on every call. A
   merchant with 500,000 payments, or many merchants interleaved, would miss cache and could scale
   non-linearly. *Would address it:* `@Param({"500", "50000", "500000"})` on the payment count, plus
   a state holding many merchants chosen at random per invocation.
3. **The harness is not the request path.** There is no HTTP, no Spring proxy or transaction, no
   JDBC or Hibernate, and no Jackson serialisation. Settlement's 0.84 µs is about 0.03% of the
   endpoint's ≈ 3.2 ms p99 (docs/gc.md section 8); the cost that matters is in what was stubbed. The
   JIT profile is also cleaner than in production's mixed workload, where the same methods may be
   compiled differently. *Would address it:* a JFR CPU profile of the service under the Lab 2 k6
   load, to see which share of request time each method really takes.
4. **Bytes per operation is not GC behaviour.** 1.4 KB/op says nothing about pause times, promotion
   or heap sizing under load. *Would address it:* the Lab 2 method: a ten-minute recording under
   steady load, reading collections and pauses.
5. **One machine, one JDK.** A laptop CPU with turbo on a Balanced power plan, Windows, JDK 25.0.4
   with compiler blackholes. Four runs on one machine agree with each other; that says nothing about
   another machine. *Would address it:* the same suite on a High-performance power plan and on a
   Linux CI runner, comparing scores and spread.

## 10. Reproduce

From a clean clone, at the repository root (one command, also in README.md):

```bash
mvn -B -q package -DskipTests && java -jar benchmarks/target/benchmarks.jar -rf json -rff benchmarks/results.json
```

About 38 minutes for the suite (measured 37 min 40 s) plus about a minute to build. Add `-prof gc`
for bytes per operation.
