# Staging Lease Safety Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enforce one cross-JVM stage owner through canonical symlink paths while refusing unsafe hard-linked or non-Unix staging databases before SQLite/work access.

**Architecture:** Create/locate the staging file, resolve it with `toRealPath`, require the Unix attribute view and `unix:nlink == 1`, then lock a never-unlinked sibling of that canonical path. Recheck the link count after acquiring the lock and open JDBC only through the canonical database path.

**Tech Stack:** Java 21, SQLite JDBC, Java NIO Unix attributes and `FileLock`, JUnit 5 subprocess integration tests, Maven Surefire.

## Global Constraints

- Preserve durable per-work SQLite commits and deterministic run scoping.
- Resolve symlink aliases to one canonical lock independent of `java.io.tmpdir`.
- Refuse hard-linked and non-Unix staging files before JDBC or pending-work access.
- Never unlink the canonical sibling lock.
- Perform no live Capital request, provider stage, promotion, D1 operation, push, or project-data replacement.

---

### Task 1: Cross-JVM safety regressions

**Files:**
- Modify: `src/test/java/io/g3tech/axetrader/history/HistoryStagingStoreTest.java`

**Interfaces:**
- Consumes: real `HistoryStagingStore.openForStage` and `nextPending` behavior in child JVMs.
- Produces: observable `work`, `waiting`, `blocked`, `unsafe`, and `error` subprocess signals.

- [x] **Step 1: Make every child use a distinct JVM temp directory**

Build each child command with `-Djava.io.tmpdir=<test-runtime>/<process-name>` rather than a shared directory. Keep the existing symlink contention assertion that the contender never emits `work`.

- [x] **Step 2: Change hard-link tests to require fail-closed behavior**

Start the canonical owner first, create a hard link while it holds the stage, then require the alias child to emit `unsafe` and no `work`. Add a second test where the hard link exists before either child starts and require both names to emit `unsafe` without work.

- [x] **Step 3: Make handoff use only canonical symlink aliases**

Use two symlinks for B and C. Require B to wait until A releases, B then reaches work, C remains blocked, and `<canonical-db>.stage.lock` retains the same filesystem file key across handoff.

- [x] **Step 4: Add unsupported-filesystem pre-JDBC rejection**

Create an empty staging entry in a ZIP filesystem at a path whose string maps to a safe host-temp shadow path. Require `openForStage` to report unsupported Unix attributes and assert the host shadow SQLite file was never created.

- [x] **Step 5: Verify RED**

Run:

```sh
./mvnw test -Dtest=HistoryStagingStoreTest#symlinkAliasCannotOwnPendingWorkConcurrently+hardLinkCreatedDuringOwnershipIsRefusedBeforeWork+databaseWithExistingHardLinkIsRefusedBeforeWork+leaseHandoffKeepsOneUnderlyingDatabaseOwner+nonUnixIdentityIsRefusedBeforeJdbcOpen
```

Expected: the current temp-root/file-key lease admits symlink and hard-link contenders with different temp roots, and it does not produce the new Unix-specific rejection.

### Task 2: Canonical Unix stage lease

**Files:**
- Modify: `src/main/java/io/g3tech/axetrader/history/HistoryStagingStore.java`

**Interfaces:**
- Produces: `StageLease.acquire(Path)` with `databasePath()` returning the canonical path safe for JDBC.

- [x] **Step 1: Prepare a new database file before identity checks**

Create parent directories and use `Files.createFile(database)` when the requested path does not exist. If another process wins creation, treat it as existing and continue to the same lease checks.

- [x] **Step 2: Validate the supported filesystem and link count**

Resolve `database.toRealPath()`, require `Files.getFileStore(realPath).supportsFileAttributeView("unix")`, read `Files.getAttribute(realPath, "unix:nlink")`, require a numeric literal `1`, and throw a preserved `IllegalStateException` containing `unsafe staging filesystem` or `hard-linked staging database` otherwise.

- [x] **Step 3: Acquire the canonical sibling lock and recheck**

Lock `realPath.resolveSibling(realPath.getFileName() + ".stage.lock")`, never delete it, then repeat the Unix/nlink validation while holding the lock. Return the real database path with the lease.

- [x] **Step 4: Open JDBC only after the lease**

In `openForStage`, prepare the file, acquire the lease, then call `connect(lease.databasePath())`. Preserve unsafe/active messages in the outer exception classifier.

- [x] **Step 5: Verify GREEN**

Run the five-test command from Task 1. Expected: all five pass with no child reaching work concurrently.

### Task 3: Artifact expectations, verification, and report

**Files:**
- Modify: `src/test/java/io/g3tech/axetrader/history/HistoryImportServiceTest.java`
- Modify: `.superpowers/sdd/2026-08-02-clean-local-price-history/task-6-report.md` (ignored by repository policy)

- [x] **Step 1: Update the stage artifact expectation**

Require the stage-only test directory to contain the active/archive fixtures, `stage.sqlite`, and the stable `stage.sqlite.stage.lock` file.

- [x] **Step 2: Run focused verification**

```sh
./mvnw test -Dtest=CapitalHistoricalPricePageSourceTest,HistoryImportRequestTest,HistoryImportServiceTest,HistoryDatabasePromoterTest,HistoryStagingStoreTest,PriceValidatorTest,DatabaseBootstrapTest,AxeTraderApplicationTests
```

Expected: all focused tests pass with zero failures/errors.

- [x] **Step 3: Append report evidence and inspect scope**

Record the spec commit `fb7d709`, RED/GREEN results, SQLite's hard-link constraint, and the focused-suite count. Run `git diff --check` and preserve the pre-existing `output/charts/chart.html` modification.

- [x] **Step 4: Commit locally**

```sh
git commit -m "fix(history): fail closed on unsafe stage aliases"
```
