# Virtual-thread migration and a carrier-pinning class initializer

Incident write-up for the Ledger settlement service, Lab 1. Measured 2026-10-22, 19:22–19:42 (UTC+01:00).

## Summary

- **Change:** HTTP requests and the application task executor moved from platform threads to virtual
  threads (`8671e15`).
- **Effect, warm, 3 + 3 interleaved runs:** throughput flat (913.9 → 951.0 req/s, ranges overlap);
  p50 slightly worse (164.7 → 176.7 ms); **p95 −27%** (663.4 → 482.7 ms) and **p99 −21%** (1,080 →
  858 ms). The bottleneck is HikariCP's 10 database connections, before and after.
- **Incident:** loading currency reference data over HTTP inside a `static {}` initializer (`3cc9f3e`)
  pinned virtual threads to their carriers on every cold start: 1 thread in `<clinit>` plus 7 waiting
  for it, about 2.3 s each, filling all 8 carriers. 20 concurrent cold requests all took 1.6–2.4 s.
- **Fix (`7309ff8`):** fetch the table once, eagerly, at startup (a Spring bean), before Tomcat opens its
  port. `jdk.VirtualThreadPinned` from `CurrencyTable`: 8, 8, 8 → **0, 0, 0**; `fixed.jfr` contains
  **no** pinned events. 20 concurrent cold requests: 17–302 ms.

## Evidence index

All raw output is in `docs/evidence/lab1-evidence.zip` (paths below are inside it); the exact
command behind every file is in `commands.log`.

| Rubric | What | Where |
|---|---|---|
| A1 | `java -version`, `mvn -v`, machine | [§1](#1-toolchain), `toolchain.txt` |
| A2, A5 | k6 summaries, 6 warm runs | [§2](#2-baseline-and-step-5-re-run), `warm/{baseline,vt}-{1,2,3}.txt` |
| A3 | Pool defaults, Hikari config, thread snapshot | [§3](#3-current-pooling), `tomcat-pool-defaults.txt`, `hikari-config.log`, `warm/baseline-1-threads-at-30s.txt` |
| A4 | Virtual threads proof | [§4](#4-migration), `PaymentControllerIT`, `vt-dump-check/` |
| A6 | Defect and cold batches | [§5](#5-planted-defect), `cold-batch/` |
| A7, A8, B3 | Recordings and pinned events | [§6](#6-pinning-proved-with-flight-recorder), `docs/jfr/pinned.jfr`, `docs/jfr/fixed.jfr`, `jfr-text/` |
| A9 | Fix and tests | [§7](#7-fix), `CurrencyTableTest` |
| A10 | Cold runs, before/after | [§8](#8-before-and-after), `cold/{pinned,fixed}-{1,2,3}.txt` |
| C1 | Clean-clone build | `clean-clone-mvn-verify.log` (22/22, no `.env`) |
| C4 | Background CPU during runs | `background-cpu.csv` (mean 13.4%, max 34.3%) |

## 1. Toolchain

Branch `feature/virtual-threads`. Every measurement used this JDK build: **25.0.4+7-LTS-189**.

```
$ java -version
java version "25.0.4" 2026-07-21 LTS
Java(TM) SE Runtime Environment (build 25.0.4+7-LTS-189)
Java HotSpot(TM) 64-Bit Server VM (build 25.0.4+7-LTS-189, mixed mode, sharing)

$ mvn -v
Apache Maven 3.9.16 (2bdd9fddda4b155ebf8000e807eb73fd829a51d5)
Maven home: C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3\plugins\maven-plugin\lib\maven3
Java version: 25.0.4, vendor: Oracle Corporation, runtime: C:\Program Files\Java\jdk-25.0.4
Default locale: en_US, platform encoding: UTF-8
OS name: "windows 11", version: "10.0", arch: "amd64", family: "windows"
```

k6 v2.2.0. Machine: Intel Core i5-8350U, 8 logical CPUs (so 8 virtual-thread carriers), 11.8 GB RAM,
Windows 11 Pro. PostgreSQL 18.6 local; k6 and the service on the same machine.

## 2. Baseline and Step 5 re-run

**Endpoint:** `GET /payments/settlement?merchantId=MR-LOAD` (500 payments, seeded by
`perf/seed-load-merchant.sql`; every response checked for `owedMinor = 2611698`).
**Profile:** `perf/settlement-load.js`: 200 concurrent VUs, no think time, 60 s.
**Procedure:** fresh JVM; `k6 run -e DURATION=15s perf/settlement-load.js` (warm-up, discarded); then
`k6 run perf/settlement-load.js`. Baseline jar `97dadd0` (platform threads) and virtual-thread jar
`8671e15`, interleaved B V B V B V; nothing else differs.

| Run | Throughput | p50 | p95 | p99 | Failed |
|---|---|---|---|---|---|
| baseline-1 | 977.8 req/s | 166.7 ms | 596.4 ms | 1,000.0 ms | 0 |
| vt-1 | 964.9 req/s | 169.1 ms | 554.7 ms | 936.1 ms | 0 |
| baseline-2 | 861.5 req/s | 163.7 ms | 680.9 ms | 1,160.0 ms | 0 |
| vt-2 | 970.4 req/s | 178.7 ms | 449.3 ms | 712.6 ms | 0 |
| baseline-3 | 902.4 req/s | 163.9 ms | 713.0 ms | 1,080.0 ms | 0 |
| vt-3 | 917.8 req/s | 182.4 ms | 444.2 ms | 926.0 ms | 1,675 (3.03%)* |
| **Baseline mean** (range) | **913.9** (861.5–977.8) | **164.7** (163.7–166.7) | **663.4** (596.4–713.0) | **1,080.0** (1,000–1,160) | |
| **Virtual mean** (range) | **951.0** (917.8–970.4) | **176.7** (169.1–182.4) | **482.7** (444.2–554.7) | **858.2** (712.6–936.1) | |

\* All TCP connection refusals (`connectex … actively refused`) in the first 6 s of that k6 run while
it opened 200 connections; the service logged no errors.

**Step 5 verdict, recorded before any explanation:** throughput **flat** (+4.1%, ranges overlap);
p50 **worse** (+7.3%, ranges separate); p95 **better** (−27.2%) and p99 **better** (−20.5%), ranges
separate. Run-to-run spread: throughput 13% (baseline), 6% (virtual).

## 3. Current pooling

No executor is configured: on `main` there is no `ThreadPoolTaskExecutor`, `ExecutorService`, `@Async`
or `server.tomcat.*` setting. The endpoint runs on Spring Boot 4.1.1's Tomcat defaults
(`tomcat-pool-defaults.txt`, from the jar's configuration metadata), backed by HikariCP
(`hikari-config.log`, effective values printed at startup):

| Pool | Core | Maximum | Queue capacity |
|---|---|---|---|
| Tomcat request threads | 10 (`threads.min-spare`) | **200** (`threads.max`) | unbounded (`threads.max-queue-capacity` = 2,147,483,647) |
| HikariCP connections | 10 (`minimumIdle`) | **10** (`maximumPoolSize`) | waiters time out after 30,000 ms |

**What bounded the baseline:** 30 s into baseline-1, a `jcmd Thread.print` snapshot showed all 200
`http-nio-8080-exec` threads in use: **189 blocked in `HikariPool.getConnection`**, 9 in PostgreSQL I/O,
1 in application code, 1 idle. With 200 VUs, Tomcat's 200-thread maximum was reached but never
exceeded, so nothing queued in Tomcat. Requests queued for one of 10 database connections.

## 4. Migration

- `application.yml`: `spring.threads.virtual.enabled: true`. Each HTTP request runs on a new virtual
  thread (`tomcat-handler-N`) instead of Tomcat's pool.
- `VirtualThreadConfig`: bean `applicationTaskExecutor` built from
  `Executors.newVirtualThreadPerTaskExecutor()`. Defining an `Executor` bean makes Boot's own
  auto-configured executor back off, so it is replaced rather than left alongside.
- **Proof:** `PaymentControllerIT.requestsAreHandledOnVirtualThreads` records
  `Thread.currentThread().isVirtual()` in a servlet filter for a real request. It passes, and fails
  when run with `-Dspring.threads.virtual.enabled=false`.
  `applicationTaskExecutorRunsTasksOnVirtualThreads` checks the executor. A JSON thread dump 7 s
  into a 200-VU run (`vt-dump-check/`) lists **192 virtual `tomcat-handler` threads**, 0
  `http-nio-8080-exec` threads and 8 carriers; 177 of the 192 are waiting in
  `HikariPool.getConnection`. (A dump taken 30 s into `vt-1` listed no virtual threads; I could not
  determine why, so it is not used.)

## 5. Planted defect

`3cc9f3e` adds a currency check to `settlementFor`. The table comes from the reference-data service,
**over real HTTP** (`ReferenceDataClient` → `java.net.http.HttpClient`; the service is
`perf/reference-data-stub`, 2 s response time), fetched inside a class initializer:

```java
final class CurrencyTable {
    private static final Map<String, Integer> MINOR_UNITS;
    static {
        MINOR_UNITS = ReferenceDataClient.fetchCurrencyTable();   // HTTP GET /currencies
    }
```

**The service was restarted cold before every run from here on.** A class initializer runs once per
JVM, so only a fresh JVM reproduces this.

20 concurrent requests on a fresh JVM, then 20 more on the same JVM (`cold-batch/`):

| Build | Cold | Warm |
|---|---|---|
| Defect `3cc9f3e` | 1,647–2,420 ms (median 1,932 ms) | 14–47 ms |
| Fix `7309ff8` | **17–302 ms (median 37 ms)** | 9–37 ms |

A thread dump 1.5 s into the defect's cold batch: 1 virtual thread inside `CurrencyTable.<clinit>`
parked with `VirtualThread.parkOnCarrierThread`; 7 stopped at `SettlementService.java:54` waiting for
initialization; 12 not yet started.

## 6. Pinning proved with Flight Recorder

Started on the running service right after startup, before traffic, then the k6 profile with no
warm-up (`commands.log`; recordings later copied to `docs/jfr/`):

```
[2026-10-22 19:34:09] $ jcmd 20296 JFR.start name=pin settings=profile filename=...\jfr2\pinned-1.jfr
[2026-10-22 19:34:10] $ k6 run perf/settlement-load.js
[2026-10-22 19:35:11] $ jcmd 20296 JFR.stop name=pin
[2026-10-22 19:35:37] $ jcmd 18148 JFR.start name=fix settings=profile filename=...\jfr2\fixed-1.jfr
[2026-10-22 19:35:37] $ k6 run perf/settlement-load.js
[2026-10-22 19:36:39] $ jcmd 18148 JFR.stop name=fix
```

`jdk.VirtualThreadPinned` is the supported replacement for the `jdk.tracePinnedThreads` system
property, which no longer exists (removed in Java 24). The `profile` settings record it with stack
traces for pins over 20 ms. Both files were opened in JDK Mission Control 9.1.2 (Event Browser →
Java Virtual Machine → Threads → Virtual Thread Pinned): `pinned.jfr` shows the Virtual Thread Pinned
events and `fixed.jfr` shows none. Check from the command line with
`jfr print --events jdk.VirtualThreadPinned docs/jfr/pinned.jfr`.

| Recording | Length | Execution samples | GCs | `jdk.VirtualThreadPinned` (from `CurrencyTable`) |
|---|---|---|---|---|
| `docs/jfr/pinned.jfr` (run 1, `3cc9f3e`) | 62 s | 4,432 | 148 | **9** (8) |
| pinned runs 2, 3 | 62 s each | | | 8 (8), 8 (8) |
| `docs/jfr/fixed.jfr` (run 1, `7309ff8`) | 62 s | 7,348 | 156 | **0** (0) |
| fixed runs 2, 3 | 62 s each | | | 0 (0), 0 (0) |

The 9th event in `pinned.jfr` is Jackson: a 20.2 ms wait for another thread to initialize
`POJOPropertiesCollector$1`, when the ~200 requests held up by `CurrencyTable` were released at once.

### The pinned event

```
jdk.VirtualThreadPinned {
  startTime = 19:34:11.065 (2026-10-22)
  duration = 2.29 s
  blockingOperation = "LockSupport.park"
  pinnedReason = "VM call to com.ledger.settlement.CurrencyTable.<clinit> on stack"
  carrierThread = "ForkJoinPool-1-worker-7" (javaThreadId = 76)
  eventThread = "tomcat-handler-5" (javaThreadId = 69, virtual)
  stackTrace = [
    java.lang.VirtualThread.parkOnCarrierThread(boolean, long) line: 830
    java.lang.VirtualThread.park() line: 755
    java.lang.System$1.parkVirtualThread() line: 2285
    java.util.concurrent.locks.LockSupport.park(Object) line: 221
    java.util.concurrent.CompletableFuture$Signaller.block() line: 1885
    java.util.concurrent.ForkJoinPool.unmanagedBlock(ForkJoinPool$ManagedBlocker) line: 4364
    java.util.concurrent.ForkJoinPool.managedBlock(ForkJoinPool$ManagedBlocker) line: 4310
    java.util.concurrent.CompletableFuture.waitingGet(boolean) line: 1919
    java.util.concurrent.CompletableFuture.get() line: 2093
    jdk.internal.net.http.HttpClientImpl.send(HttpRequest, HttpResponse$BodyHandler) line: 902
    jdk.internal.net.http.HttpClientFacade.send(HttpRequest, HttpResponse$BodyHandler) line: 133
    com.ledger.settlement.ReferenceDataClient.fetchCurrencyTable() line: 27
    com.ledger.settlement.CurrencyTable.<clinit>() line: 18
    com.ledger.settlement.SettlementService.settlementFor(String) line: 54
    ...
  ]
}
```

- **Duration** 2.29 s; **pinning reason** `VM call to com.ledger.settlement.CurrencyTable.<clinit> on stack`.
- **Frame that cannot unmount:** `com.ledger.settlement.CurrencyTable.<clinit>()`, the class initializer.
  The JVM runs it as a VM call; a virtual thread cannot unmount while a VM call is on its stack, so
  `park` falls back to `parkOnCarrierThread` and the carrier blocks with it.
- **Blocking call inside it:** `ReferenceDataClient.fetchCurrencyTable()` → `HttpClient.send` →
  `CompletableFuture.get`, waiting for the reference-data service.
- The 7 other `CurrencyTable` events are threads at `SettlementService.settlementFor` line 54,
  `blockingOperation = "Object.wait"`, `pinnedReason = "Waited for initialization of
  com.ledger.settlement.CurrencyTable by another thread"`, 2.17–2.35 s each.

**Pre-Java-24 advice about `synchronized`:** before Java 24, a virtual thread blocking inside a
`synchronized` block pinned its carrier, so the standard advice was to replace `synchronized` with
`ReentrantLock`, which virtual threads can block on and still unmount. JEP 491 (*Synchronize Virtual
Threads without Pinning*, Java 24) removed that limitation, and this pin has no monitor in our code at
all: it comes from a class initializer, which JEP 491 does not cover, so swapping to `ReentrantLock`
would change nothing here.

## 7. Fix

`7309ff8`: `CurrencyTable` is a Spring bean that fetches the table **once, in its constructor**, and keeps
`Map.copyOf(...)`. Spring creates singletons before the embedded Tomcat opens its port, so the HTTP
call runs on the startup (platform) thread before any request exists. Request threads only read an
immutable map. Same semantics: one fetch per JVM, every request sees the same complete table,
identical validation; MR-4471 still settles to 124,469.

Not chosen: computing the table on a bounded platform-thread executor and waiting on the result. That
also avoids pinning (waiting on a `Future` unmounts), but the first requests would still wait 2 s.

`CurrencyTableTest`:
- 2,000 virtual threads released together by a `CountDownLatch` all see the same complete table, and
  the downstream fetch runs **exactly once**.
- The table is an immutable snapshot of what was fetched.
- A JFR `Recording` (`jdk.VirtualThreadPinned`, threshold 0) sees **no** pins while those threads read
  it, and **does** see pins for a positive control that blocks in a `static {}` block.

Suite 22/22 (`mvn -B verify`, including 5 Testcontainers ITs), also from a clean clone with no `.env`.

## 8. Before and after

Cold runs: fresh JVM, JFR started as in §6, no warm-up, 200 VUs × 60 s, defect and fix interleaved
P F P F P F (`cold/`):

| Run | Throughput | p50 | p95 | p99 | Failed |
|---|---|---|---|---|---|
| pinned-1 / fixed-1 | 695.3 / 748.2 req/s | 217.7 / 211.5 ms | 722.5 / 702.6 ms | 1,230 / 1,190 ms | 392* / 0 |
| pinned-2 / fixed-2 | 643.7 / 794.1 req/s | 234.9 / 187.6 ms | 753.7 / 679.0 ms | 1,460 / 1,230 ms | 614* / 0 |
| pinned-3 / fixed-3 | 884.9 / 932.5 req/s | 167.8 / 168.1 ms | 515.9 / 527.0 ms | 1,040 / 920 ms | 0 / 0 |

\* TCP connection refusals (`connectex … actively refused`) in the first second of the run, while k6
opened its 200 connections; the service logged no errors. They occurred in 2 of 3 defect runs and 0 of
3 fixed runs; the cause is not established.

| Run (mean of 3) | Throughput (req/s) | p50 | p95 | p99 |
|---|---|---|---|---|
| Baseline, platform threads (warm) | 913.9 | 164.7 ms | 663.4 ms | 1,080.0 ms |
| Virtual threads (warm) | 951.0 | 176.7 ms | 482.7 ms | 858.2 ms |
| Virtual threads + pinning defect (cold) | 741.3 | 206.8 ms | 664.0 ms | 1,243.3 ms |
| Fixed (cold) | 824.9 | 189.1 ms | 636.2 ms | 1,113.3 ms |

Cold pinned vs fixed: every range overlaps (throughput 643.7–884.9 vs 748.2–932.5), so the aggregate
figures show **no difference beyond noise**. The defect pins 8 requests for ~2.3 s once per start,
out of ~44,000. The fix is proven by the direct measurements: pins 8, 8, 8 → 0, 0, 0, and cold
first-request latency 1.6–2.4 s → 17–302 ms.

## Explanation and limits

- **Why throughput stayed flat with virtual threads:** both versions wait on the same 10 database
  connections (§3, §4). More threads mean more waiters, not more throughput. The better tails are
  consistent with 200 OS threads competing for 8 cores (plus k6) being replaced by cheap parking, but
  that is a hypothesis this data does not prove.
- k6 runs on the same 8 cores as the service; background CPU from other apps averaged 13.4%.
- Every comparison changed exactly one commit; each configuration ran 3 times, interleaved.
- `StartupWarmUp` (PR `perf/load-harness`, in both builds) warms the request path before the app
  reports `Started`. Without it, Hibernate and class loading added 55–196 short framework pins per cold
  run, and `fixed.jfr` could not have been empty.
