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
