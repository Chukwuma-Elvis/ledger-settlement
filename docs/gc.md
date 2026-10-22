# Garbage collection under load: settlement endpoint

Lab 2 tuning report for the Ledger settlement service. Branch `feature/gc-tuning` (from
`feature/virtual-threads`, so the service as it will ship: virtual threads, Spring Boot 4.1.1,
JDK 25.0.4+7-LTS-189). Measured 2026-10-22.

## 1. Starting point

```
$ java -XX:+PrintFlagsFinal -version | grep -E "MaxHeapSize|UseG1GC"
   size_t MaxHeapSize                              = 3170893824                                {product} {ergonomic}
     bool UseG1GC                                  = true                                      {product} {ergonomic}
```

Machine: 8 logical CPUs (Intel Core i5-8350U), 11.81 GB RAM (12,679,237,632 bytes), Windows 11 Pro.
Both values are JVM ergonomics, not configuration: `MaxHeapSize` is `MaxRAMPercentage` = 25% of
physical memory (12,679,237,632 / 4 ≈ 3.17 GB, rounded to the heap region alignment), and G1 is
chosen because the machine is "server class" (≥ 2 CPUs and ≥ 1.8 GB). Related ergonomic values:
`InitialHeapSize` 199,229,440, `ParallelGCThreads` 8, `ConcGCThreads` 2.

## 2. Load

`perf/steady-load.js` (k6): **constant arrival rate of 300 requests/s** against
`GET /payments/settlement?merchantId=MR-LOAD`, 1 minute warm-up stage, then the **measured 10
minutes**. Run unchanged with `k6 run perf/steady-load.js`.

- **Why this endpoint:** it is the most expensive request the service serves. Each call reads and
  sums every payment of a merchant (500 rows for MR-LOAD, seeded by `perf/seed-load-merchant.sql`).
  `POST /payments` writes one row; `GET /payments/{id}` reads one.
- **Why 300 req/s:** about a third of the ~950 req/s this endpoint sustained flat-out in Lab 1. The
  service is busy and allocating continuously but not saturated, so latency reflects the service
  rather than queueing, and the arrival rate is identical in every run (the k6 summary shows 0
  dropped iterations).

## 3. Baseline recordings

**Command** (`commands.log` in the evidence zip; the brief's flags plus `delay=90s`):

```
java -XX:StartFlightRecording=delay=90s,duration=10m,filename=gc-baseline-<n>.jfr,settings=profile -jar baseline-8558f8a.jar
k6 run perf/steady-load.js        # started exactly 30 s after the JVM
```

The JVM starts at T0 and k6 at T0+30 s, so the 1-minute warm-up ends at T0+90 s, when the
recording starts. **The 10-minute recording covers exactly the 10-minute measured stage**, after
startup, JIT warm-up and connection set-up. Every recording is 600 s long.

**Steady state, confirmed per minute** (`gc-baseline.jfr`, i.e. run 2): collections per minute
**142, 143, 142, 143, 142, 143, 143, 143, 142, 143**; sampled allocation **198 MB/s in minutes 2–10**.
Minute 1 reads 426 MB/s in every run, a start-of-recording effect of the allocation sampler. The
GC count, which is not sampled, is flat from the first minute.

**Runs:** 4 baseline runs, of which **2 and 4 are clean**. Run 1 ran while an unrelated process used
about 33% of the CPU (stopped at 20:37:28, before run 2). During run 3 my own analysis tooling used
12–25% of the CPU for about 5 minutes. Both are kept in the evidence zip and excluded below.

## 4. Four baseline numbers

From `gc-baseline.jfr` (run 2). JDK Mission Control views: **Memory** page (*Total Allocation*,
and the *Allocation by Class* table) and **Garbage Collections** page (the GC table, sorted by
*Longest Pause*). Cross-checked from the command line with `jfr view allocation-by-class`,
`jfr view gc-pauses` and `jfr print --events jdk.GarbageCollection` (files in `analysis/`).

| Measure | Run 2 (`gc-baseline.jfr`) | Run 4 (repeat) |
|---|---|---|
| Total allocation rate | **243.5 MB/s** (146.1 GB in 600 s) | 243.5 MB/s |
| Top 3 allocating classes | **`byte[]` 15.5%, `int[]` 12.9%, `sun.util.calendar.Gregorian$Date` 8.6%** (4th: `java.util.GregorianCalendar` 8.5%) | `byte[]` 16.6%, `int[]` 12.4%, `GregorianCalendar` 6.8% |
| Collection count | **1,588** (all young, `G1New`) | 1,780 (1,772 `G1New`, 8 `G1Old`) |
| Longest pause | **3.2 ms** | 10.3 ms |

Allocation rate cross-check: summing per-thread allocation counters
(`jdk.ThreadAllocationStatistics`) gives 220.6 MB/s for run 2, within 10% of the sampled figure.
`jfr view gc-pauses` for run 2: 1,588 pauses, median 1.00 ms, p99 1.73 ms, total 1.64 s (0.27% of
the run). k6, run 2: 300.0 req/s, p99 4.04 ms, 0 failed.

## 5. Classification (written before any change)

**Allocation pressure.** The service allocates about 240 MB/s to serve 300 requests/s, roughly
800 KB per request. That drives about 2.6 young collections per second. The heap after each
collection is only 47–50 MB, so almost everything allocated is garbage within one request, and
the profile is dominated by a few allocation sites rather than spread evenly.

**Why it is not a pause problem:** pauses are short and rare in total time: the longest is 3.2 ms
(10.3 ms in run 4), the median 1.0 ms, and all pauses together are 0.27% of the run. p99 request
latency (4.04 ms) is barely above the median (1.79 ms). A larger heap or a different collector would
spread the same garbage over fewer or concurrent collections, but would not reduce the work that
creates it.

**The hot allocation site** (`jfr view allocation-by-site`, confirmed with full stack traces in
`analysis/baseline-2-calendar-allocation-stack.txt`):

```
SettlementService.settlementFor
  -> PaymentRepository.findByMerchantId               (loads 500 full PaymentEntity rows)
    -> Hibernate EntityInitializerImpl.extractConcreteTypeStateValues
      -> PgResultSet.getTimestamp(int, Calendar)      (the recorded_at column)
        -> TimestampUtils -> new GregorianCalendar / Calendar$Builder / TimeZone.clone
```

About **45% of all allocation** is that timestamp conversion: `Calendar.<init>` 13.8%,
`Gregorian.newCalendarDate` 8.6%, `Calendar$Builder.build` 8.5%, `Instant.create` 4.5%,
`CalendarProviderImpl.getInstance` 3.3%, `TimeZone.clone` 3.3%, `TimestampUtils` 3.8%. Another
~12% is Hibernate's persistence context tracking 500 managed entities per request
(`StatefulPersistenceContext.addEntry` 5.1%, `getOrInitializeNewHolder` 3.1%,
`EntityEntryContext.addEntityEntry` 1.9%, `IdentityHashMap.resize` 1.8%). **Settlement only reads
`amountMinor` and `currency`**: it never uses `recordedAt` or needs managed entities.

**Planned remedy (one variable, a code change):** make the settlement query fetch only the two
columns it uses, as a read-only projection rather than managed entities. No JVM flag changes.

*(Sections 1–5 were committed in `29bc71f`, before any code change. Everything below came after.)*

## 6. Remedy: one code change at the hot site

The diagnosis was allocation pressure, so the remedy is in code. No JVM flag, heap size or
collector changes; the launch command is identical apart from the jar.

**The change** (`git diff 8558f8a e794e7b -- src/main`: 3 files, 18 insertions, 3 deletions):

```diff
 // PaymentRepository
+    List<PaymentAmount> findAmountsByMerchantId(String merchantId);   // derived query, record projection
 // PaymentAmount (new)
+public record PaymentAmount(long amountMinor, String currency) { }
 // SettlementService.settlementFor
-        List<PaymentEntity> payments = paymentRepository.findByMerchantId(merchantId);
+        List<PaymentAmount> payments = paymentRepository.findAmountsByMerchantId(merchantId);
-        long totalMinor = payments.stream().mapToLong(PaymentEntity::getAmountMinor).reduce(0L, Math::addExact);
+        long totalMinor = payments.stream().mapToLong(PaymentAmount::amountMinor).reduce(0L, Math::addExact);
-        String currency = payments.getFirst().getCurrency();
+        String currency = payments.getFirst().currency();
```

The SQL changes from selecting all five columns into managed entities to (logged with
`spring.jpa.show-sql=true`):

```
select pe1_0.amount_minor,pe1_0.currency from payments pe1_0 where pe1_0.merchant_id=?
```

**Why this site, and not the alternatives:**
- It is the site the profile named: about 57% of allocation (timestamp conversion plus the
  persistence context) came from loading data settlement never reads.
- *Sum in the database* (`select sum(amount_minor)`) would allocate even less, but it needs
  hand-written JPQL. The repository has none, and it would change how the currency check works.
- *Read-only entities* (Hibernate query hints) would skip dirty-checking snapshots but still convert
  every `recorded_at` timestamp: the largest single cost.
- *JVM flags* (`-Xmx`, `-XX:+UseZGC`): pauses are already 1 ms median and 0.27% of time. A flag
  would move the same garbage around without reducing it.

**A first attempt that made it worse, kept in the history.** The first version (`503932d`) used an
*interface* projection. Measured twice under the same 10-minute load (`proxy-*` in the evidence
zip): allocation rose from 243.5 to **415.8 MB/s** (+71%) and collections to 1,977 and 2,416. The
timestamp allocations were gone, but Spring Data builds interface projections as **proxies, one per
row**: `java.lang.reflect.Method.copy` 20.7%, `AdvisedSupport.adviceChanged` 7.5%,
`ProxyProjectionFactory.createProjection` 4.3%. Switching the same projection to a **record**
(`e794e7b`) makes Spring Data use a constructor call: same SQL, no proxies. A 60 s check showed
64.3 MB/s before the full runs below.

## 7. Tuned runs

Identical to the baseline runs: same script, rate, duration, machine, database and seed data,
same procedure (JVM at T0, k6 at T0+30 s, recording T0+90 s to T0+690 s). Only the jar differs.
From `commands.log`:

```
java -XX:StartFlightRecording=delay=90s,duration=10m,filename=gc-baseline-2.jfr,settings=profile -jar baseline-8558f8a.jar
java -XX:StartFlightRecording=delay=90s,duration=10m,filename=gc-tuned-1.jfr,settings=profile -jar tuned-e794e7b.jar
```

Two tuned runs, both 600 s, 180,000 requests at 300.0 req/s, 0 failed, 0 dropped. Background CPU
(everything except the service, k6 and PostgreSQL): tuned 4.3% mean, baseline run 4 4.3% mean.
Steady state: collections per minute 53, 45, 40, 40, 40, 40, 40, 41, 40, 40 (run 1); sampled allocation
40 MB/s in minutes 2–10.

## 8. Baseline vs tuned

Baseline = runs 2 and 4 (`8558f8a`); tuned = runs 1 and 2 (`e794e7b`). A change is called **no
change** when the two runs of each side overlap.

| Measure | Baseline (run 2 / run 4) | Tuned (run 1 / run 2) | Change |
|---|---|---|---|
| Allocation rate | 243.5 / 243.5 MB/s | **49.8 / 49.8 MB/s** | **−79.5%** |
| Collection count (10 min) | 1,588 / 1,780 | **465 / 455** | **−72.7%** |
| Longest pause | 3.2 / 10.3 ms | 7.6 / 12.4 ms | **no change** (ranges overlap) |
| p99 request latency | 4.04 / 6.09 ms | **3.38 / 3.02 ms** | **−36.8%** |
| Throughput | 300.0 / 300.0 req/s | 300.0 / 300.0 req/s | no change (fixed arrival rate) |
| *p50 request latency* | 1.79 / 1.99 ms | 1.44 / 1.44 ms | −23.8% |
| *Total GC pause time* | 1.64 / 2.11 s | 0.57 / 0.54 s | −70.4% |
| *Heap after GC / committed max* | 44–63 MB / 130–145 MB | 38–43 MB / 111 MB | lower |
| *Startup* | 11.0 / 13.2 s | 12.4 / 11.8 s | no change |

Allocation cross-check (`jdk.ThreadAllocationStatistics`): 220.6 → 44.8 MB/s (−79.7%), agreeing with
the sampled figure. `jfr view gc-pauses`, tuned run 1: 484 pauses, median 1.01 ms, p99 4.07 ms.

What is left is mostly unavoidable: the driver reading row bytes (`PGStream.receiveTupleV3` 20.8%),
virtual-thread stack chunks when requests park (`Continuation.doYield` 11.0%), and `Instant.create`
10.1%. The latter is not from this query, which no longer reads a timestamp; its source was not
investigated.

## 9. The trade

No regression was measured in throughput, memory footprint or startup time: the arrival rate is
fixed, the heap shrank, and startup times overlap. Throughput *capacity* was not measured here, only
latency at 300 req/s. The cost is in the code: settlement now depends on a second repository method
and a projection type that must change if settlement ever needs another column.

The trade is acceptable for this service because settlement is its most expensive and busiest read,
and the projection cuts allocation by four-fifths and p99 latency by a third while returning
identical results (the IT still settles MR-4471 to 124,469). It would stop being acceptable if
settlement had to return per-payment detail such as timestamps: then the projection would have to
widen towards the full entity, and most of the saving would go with it.

## 10. Evidence and reproduction

Recordings: `docs/jfr/gc-baseline.jfr` (baseline run 2) and `docs/jfr/gc-tuned.jfr` (tuned run 1).
Everything else is in `docs/evidence/lab2-evidence.zip`: `commands.log` (every command, timestamped),
`heap-flags.txt`, k6 output and service logs for every run (`runs/`), the analysis of every
recording including the `jfr view` outputs and the calendar allocation stack (`analysis/`),
background CPU every 10 s, the 60 s record-projection check, and `clean-clone-mvn-verify.log`
(`feature/gc-tuning` cloned fresh with no `.env`: `mvn -B verify` 22/22, BUILD SUCCESS). No JVM flags
are applied anywhere: the change is in code only.

```bash
psql -h localhost -U ledger -d ledger -f perf/seed-load-merchant.sql
java perf/reference-data-stub/ReferenceDataStub.java 8091 2000            # leave running
mvn -B package -DskipTests
java -XX:StartFlightRecording=delay=90s,duration=10m,filename=gc-tuned.jfr,settings=profile -jar target/ledger-settlement.jar
k6 run perf/steady-load.js                                                 # start 30 s after the JVM
```
