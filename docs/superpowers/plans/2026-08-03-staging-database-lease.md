# Staging Database Lease Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make one staging SQLite database have exactly one stage owner across processes, symlink aliases, hard-link aliases, and owner handoff.

**Architecture:** Replace the path-named, deleted sidecar with an exclusive `FileLock` on a stable, never-unlinked sidecar named from the staging database's filesystem file key (device/inode identity) in one process-shared runtime directory. Connect first so a new stage has an underlying file identity, acquire the identity lease before schema/work access, hold it for the `HistoryStagingStore` lifetime, and release without deleting the lock file.

**Tech Stack:** Java 21, SQLite JDBC, JUnit 5, AssertJ, OS file locks, Maven Surefire subprocesses.

## Global Constraints

- Preserve deterministic run scoping and all prior closure/pacing behavior.
- A second stage owner must be refused before reading pending provider work.
- Symlink and hard-link spellings of the same SQLite file share one lease.
- Releasing one owner must not create a split lock identity during handoff.
- Perform no live Capital request, provider stage, promotion, D1 operation, or push.

---

### Task 1: Cross-process alias and handoff regressions

**Files:**
- Modify: `src/test/java/io/g3tech/axetrader/history/HistoryStagingStoreTest.java`

**Interfaces:**
- Consumes: `HistoryStagingStore.openForStage(Path, HistoryImportRequest, Duration)` and `nextPending()`.
- Produces: subprocess regressions that observe `waiting`, `work`, and `release` signal files.

- [x] **Step 1: Write failing alias tests**

Add a public nested subprocess main that opens the real store and writes a `work` signal only after `nextPending()` is reachable. Add separate symlink and hard-link tests: process A holds the original path while process B opens the alias; B must emit `blocked` and must never emit `work`.

- [x] **Step 2: Write the failing handoff test**

Start A on the original path and a retrying B on a symlink. Require B to emit `waiting`, release A, require B to emit `work`, then start C on a hard link and require C to emit `blocked` without `work` until B releases.

- [x] **Step 3: Verify RED**

Run:

```sh
./mvnw test -Dtest=HistoryStagingStoreTest#symlinkAliasCannotOwnPendingWorkConcurrently+hardLinkAliasCannotOwnPendingWorkConcurrently+leaseHandoffKeepsOneUnderlyingDatabaseOwner
```

Expected: alias contenders reach `nextPending()` under the current path-derived sidecars, so the tests fail on `work`/missing `blocked` or `waiting` assertions.

### Task 2: Underlying database-identity lease

**Files:**
- Modify: `src/main/java/io/g3tech/axetrader/history/HistoryStagingStore.java`

**Interfaces:**
- Consumes: the staging database path after SQLite connection creation.
- Produces: `StageLease.acquire(Path)` backed by one stable sidecar key for the real database file identity.

- [x] **Step 1: Acquire after database creation**

Change `openForStage` to connect before acquiring the lease, then acquire before enabling foreign keys, applying schema, validating resume identity, or reading work.

- [x] **Step 2: Replace the sidecar implementation**

Read `BasicFileAttributes.fileKey()` through `database.toRealPath()`, fail closed if the filesystem exposes no stable key, combine the file store and key into a UUID lock name under `${java.io.tmpdir}/axe-trader-stage-leases`, retain `OverlappingFileLockException` handling, and close only the lock/channel:

```java
Object fileKey = Files.readAttributes(database.toRealPath(), BasicFileAttributes.class).fileKey();
Path lockPath = Path.of(System.getProperty("java.io.tmpdir"), "axe-trader-stage-leases")
        .resolve(UUID.nameUUIDFromBytes((Files.getFileStore(realPath) + "\n" + fileKey)
                .getBytes(StandardCharsets.UTF_8)) + ".lock");
FileLock lock = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).tryLock();
```

Never create or delete a sidecar pathname.

- [x] **Step 3: Verify GREEN**

Run the three tests from Task 1. Expected: all pass; alias contenders are refused before pending work and handoff retains a single lock identity.

### Task 3: Regression verification, report, and commit

**Files:**
- Modify: `.superpowers/sdd/2026-08-02-clean-local-price-history/task-6-report.md`
- Commit: this plan, production code, and tests; the report remains ignored by repository policy.

- [x] **Step 1: Run the full focused suite**

```sh
./mvnw test -Dtest=CapitalHistoricalPricePageSourceTest,HistoryImportRequestTest,HistoryImportServiceTest,HistoryDatabasePromoterTest,HistoryStagingStoreTest,PriceValidatorTest,DatabaseBootstrapTest,AxeTraderApplicationTests
```

Expected: all focused tests pass with zero failures/errors.

- [x] **Step 2: Append evidence and inspect scope**

Record RED/GREEN commands and results in `task-6-report.md`. Run `git diff --check`, inspect staged paths, and preserve the pre-existing `output/charts/chart.html` change.

- [x] **Step 3: Commit**

```sh
git commit -m "fix(history): bind stage lease to database"
```
