# Reusable Historical Price Re-ingestion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provide a reusable, fail-closed Capital.com historical-price re-ingestion command that stages, audits, and explicitly promotes a selected instrument's history without risking the active database.

**Architecture:** A small history-import package separates provider pagination, SQLite staging/audit, and file promotion. A Spring `ApplicationRunner`, enabled only by an explicit property, authenticates through the existing Capital client and executes either a read-only probe, staged import, or explicit promotion. The active database is never written until the staged audit has passed and promotion is requested.

**Tech Stack:** Java 21, Spring Boot 4, SQLite JDBC, existing Capital REST client/authentication, JUnit 5 + AssertJ, Maven.

## Global Constraints

- The importer accepts required `epic`, `resolution`, half-open `from`/`to`, and staging-database path parameters; source defaults to `capital`.
- Page the Capital historical-prices endpoint with no more than 1,000 bars per request and preserve source bid/ask OHLC values verbatim.
- Import metadata stores each request bounds and SHA-256 response payload hash.
- Staging schema uniqueness is `(source, epic, resolution, snapshot_time_utc)`.
- A discovery import must reject any window intersecting `2026-01-01T00:00:00Z` through `2026-05-02T00:00:00Z` before authentication or network access.
- Promotion requires zero duplicates, zero non-finite/non-positive fields, complete coverage outside recognised session closures, and zero crossed bid/ask OHLC fields.
- The existing `data/axe-trader.sqlite` is never modified during probe, staging, or audit; promotion creates a dated backup and performs an atomic replacement.
- The first operational probe is `US500`, `MINUTE`, `2025-01-20T16:20:00Z` to `2025-01-20T16:23:00Z`.
- Do not run discovery final OOS validation or otherwise read the protected OOS range.
- Commit after every task and push after every commit.

---

### Task 1: Reusable request, source, and page model

**Files:**

- Create: `src/main/java/io/g3tech/axetrader/history/HistoryImportRequest.java`
- Create: `src/main/java/io/g3tech/axetrader/history/HistoricalPricePageSource.java`
- Create: `src/main/java/io/g3tech/axetrader/history/CapitalHistoricalPricePageSource.java`
- Test: `src/test/java/io/g3tech/axetrader/history/HistoryImportRequestTest.java`
- Test: `src/test/java/io/g3tech/axetrader/history/CapitalHistoricalPricePageSourceTest.java`

**Interfaces:**

- Consumes: the existing `AuthenticationClient`, `ApiClient`, `ConversationContext`, `GetPricesRequest`, and Capital price DTOs.
- Produces: `HistoryImportRequest(String epic, String resolution, Instant from, Instant to, Path stagingDatabase, String source, boolean discoveryWindow)` and `HistoricalPricePageSource.fetch(HistoryImportRequest request, Instant fromInclusive, Instant toExclusive, int maxBars)`.

- [ ] **Step 1: Write failing request-validation tests**

```java
@Test
void discoveryRequestRejectsProtectedOosBeforeItCanBeUsed() {
    assertThatIllegalArgumentException().isThrownBy(() -> request(
            "US500", "MINUTE", "2026-01-01T00:00:00Z", "2026-01-01T00:01:00Z", true));
}

@Test
void requestRequiresAValidHalfOpenWindowAndStagingPath() {
    assertThatIllegalArgumentException().isThrownBy(() -> request(
            "", "MINUTE", "2025-01-01T00:00:00Z", "2025-01-02T00:00:00Z", false));
}
```

- [ ] **Step 2: Run tests to verify failure**

Run: `./mvnw test -Dtest=HistoryImportRequestTest`

Expected: compilation fails because `HistoryImportRequest` does not exist.

- [ ] **Step 3: Implement the immutable request and Capital adapter**

```java
public interface HistoricalPricePageSource {
    ImportedPage fetch(HistoryImportRequest request, Instant fromInclusive, Instant toExclusive, int maxBars);
}

public record ImportedPage(Instant requestedFrom, Instant requestedTo, String payloadHash,
                           List<HistoricalPrice> prices) {}
```

The adapter creates one authenticated session lazily, calls `ApiClient.getPrices` with the requested window and `maxBars`, maps each DTO field directly to `HistoricalPrice`, and hashes the canonical request bounds plus response price fields. It must not call `HistoricalPriceRepository`.

- [ ] **Step 4: Write and run adapter mapping tests**

```java
@Test
void preservesBidAskOhlcValuesAndHashesTheReturnedPage() {
    ImportedPage page = source.fetch(request, from, to, 1_000);
    assertThat(page.prices().getFirst().getCloseBid()).isEqualTo(6023.4);
    assertThat(page.prices().getFirst().getCloseAsk()).isEqualTo(6023.3);
    assertThat(page.payloadHash()).hasSize(64);
}
```

Run: `./mvnw test -Dtest=HistoryImportRequestTest,CapitalHistoricalPricePageSourceTest`

Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/g3tech/axetrader/history src/test/java/io/g3tech/axetrader/history
git commit -m "feat(history): add reusable Capital history page source"
git push
```

### Task 2: Staging database, audit, and promotion services

**Files:**

- Create: `src/main/java/io/g3tech/axetrader/history/HistoryStagingStore.java`
- Create: `src/main/java/io/g3tech/axetrader/history/HistoryImportAudit.java`
- Create: `src/main/java/io/g3tech/axetrader/history/HistoryDatabasePromoter.java`
- Test: `src/test/java/io/g3tech/axetrader/history/HistoryStagingStoreTest.java`
- Test: `src/test/java/io/g3tech/axetrader/history/HistoryDatabasePromoterTest.java`

**Interfaces:**

- Consumes: `ImportedPage` from Task 1 and staging `Path`.
- Produces: `HistoryImportAudit` with counts for rows, distinct timestamps, duplicates, invalid fields, coverage gaps, and each crossed field; `HistoryDatabasePromoter.promote(staging, active)` only returns after an atomic replacement and backup.

- [ ] **Step 1: Write failing persistence and audit tests**

```java
@Test
void auditFailsForCrossedCloseEvenWhenEveryPriceIsPositive() {
    store.write(pageWithCloseBid(6023.4).andCloseAsk(6023.3));
    assertThat(store.audit(request).isPromotable()).isFalse();
    assertThat(store.audit(request).crossedCloseCount()).isEqualTo(1);
}

@Test
void repeatedPageCannotCreateDuplicateTimestampRows() {
    store.write(page);
    store.write(page);
    assertThat(store.audit(request).duplicateCount()).isZero();
    assertThat(store.audit(request).rowCount()).isEqualTo(1);
}
```

- [ ] **Step 2: Run tests to verify failure**

Run: `./mvnw test -Dtest=HistoryStagingStoreTest,HistoryDatabasePromoterTest`

Expected: compilation fails because the staging/audit types do not exist.

- [ ] **Step 3: Implement schema and audit**

Create the staging tables `historical_price` and `history_import_page`, add the unique index on source, epic, resolution, and timestamp, and use one transaction per page. `audit` must compute every required metric directly from staging SQL and return `promotable=false` for an invalid field, crossed field, duplicate, or non-session coverage gap.

- [ ] **Step 4: Implement fail-closed promotion**

```java
public Path promote(Path stagingDatabase, Path activeDatabase, HistoryImportAudit audit) {
    if (!audit.isPromotable()) {
        throw new IllegalStateException("refusing to promote a failed history import audit");
    }
    Path backup = activeDatabase.resolveSibling(activeDatabase.getFileName() + ".backup-" + clock.instant());
    Files.copy(activeDatabase, backup);
    Files.move(stagingDatabase, activeDatabase, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    return backup;
}
```

Use a filesystem-safe timestamp format in the backup name and fall back only from `ATOMIC_MOVE` to a replace operation after the backup exists. Test that a failed audit leaves active bytes unchanged and a successful promotion creates a readable backup.

- [ ] **Step 5: Run focused tests**

Run: `./mvnw test -Dtest=HistoryStagingStoreTest,HistoryDatabasePromoterTest`

Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/g3tech/axetrader/history src/test/java/io/g3tech/axetrader/history
git commit -m "feat(history): stage and audit reingested price history"
git push
```

### Task 3: Import orchestration and explicit command modes

**Files:**

- Create: `src/main/java/io/g3tech/axetrader/history/HistoryReingestionService.java`
- Create: `src/main/java/io/g3tech/axetrader/history/HistoryReingestionRunner.java`
- Modify: `src/main/resources/application.yaml`
- Test: `src/test/java/io/g3tech/axetrader/history/HistoryReingestionServiceTest.java`

**Interfaces:**

- Consumes: Task 1 page source, Task 2 staging/audit/promoter, and configuration under `axe-trader.history-import`.
- Produces: explicit modes `probe`, `stage`, and `promote`; normal application operation is unchanged unless `axe-trader.history-import.enabled=true`.

- [ ] **Step 1: Write failing orchestration tests**

```java
@Test
void probeFetchesExactlyTheRequestedRangeAndDoesNotCreateOrModifyDatabases() {
    service.probe(request);
    assertThat(source.calls()).containsExactly(call(from, to, 1_000));
    assertThat(Files.exists(stagingDatabase)).isFalse();
}

@Test
void stageStopsAtTheFirstFailedAuditAndNeverInvokesPromotion() {
    assertThatThrownBy(() -> service.stage(request)).isInstanceOf(HistoryAuditFailedException.class);
    verify(promoter, never()).promote(any(), any(), any());
}
```

- [ ] **Step 2: Run test to verify failure**

Run: `./mvnw test -Dtest=HistoryReingestionServiceTest`

Expected: compilation fails because `HistoryReingestionService` does not exist.

- [ ] **Step 3: Implement modes and pagination**

`probe` calls the page source for exactly the requested interval and logs the returned audit without creating files. `stage` requests sequential non-overlapping pages, advances from the maximum received timestamp plus one resolution interval, rejects an empty/non-advancing page, writes all pages to staging, and throws on a non-promotable audit. `promote` opens an existing staging database, recomputes its audit, and delegates to the promoter only when it is promotable.

The runner reads explicit Spring properties (`enabled`, `mode`, `epic`, `resolution`, `from`, `to`, `staging-database`, `active-database`, `discovery-window`) and fails on missing/unknown values. It runs only when enabled; default configuration leaves ordinary application startup unchanged.

- [ ] **Step 4: Run focused and regression tests**

Run: `./mvnw test -Dtest=HistoryReingestionServiceTest,HistoryImportRequestTest,HistoryStagingStoreTest,DatabaseBootstrapTest`

Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/g3tech/axetrader/history src/main/resources/application.yaml src/test/java/io/g3tech/axetrader/history
git commit -m "feat(history): add explicit reingestion command"
git push
```

### Task 4: Operational documentation and development-history probe

**Files:**

- Modify: `docs/dev-environment.md`
- Modify: `TODO.md`

**Interfaces:**

- Consumes: the Task 3 command modes.
- Produces: documented reusable commands and an evidence-backed record of the known-bad US500 probe.

- [ ] **Step 1: Document the exact command forms**

Add commands for the known-bad probe, a development-only staged US500 import, audit inspection, explicit promotion, and snapshot compression. State that `stage` never replaces the active DB and that promotion is refused for crossed fields. Include a generic other-instrument example with all required parameters.

- [ ] **Step 2: Run the known-bad read-only probe**

Run the `probe` command for `US500`, `MINUTE`, `2025-01-20T16:20:00Z` through `2025-01-20T16:23:00Z`; capture the returned raw-field audit in `TODO.md`.

Expected: no active or staging database is created or changed.

- [ ] **Step 3: Record the evidence and decision**

If the provider returns any crossed field, record the exact counts and stop without a staged import or promotion. If it returns no crossed field, stage the development-only import, inspect its audit, promote only if every gate passes, rebuild the compressed snapshot, and resume Task 12 from the discovery command. Never substitute fabricated results or relax a gate.

- [ ] **Step 4: Run final verification**

Run:

```bash
./mvnw test
cd dashboard && npm test && npm run typecheck && npm run build
cd ..
git diff --check
git status --short
```

Expected: every test/check passes; generated staging and active SQLite files remain ignored; only intended source, test, and documentation changes are ready to commit.

- [ ] **Step 5: Commit**

```bash
git add docs/dev-environment.md TODO.md
git commit -m "docs(history): record reingestion verification"
git push
```

