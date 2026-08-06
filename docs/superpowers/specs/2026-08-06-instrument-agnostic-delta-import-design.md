# Instrument-Agnostic Delta Price Import Design

## Goal

Keep the local clean price dataset current with a repeatable, instrument-agnostic top-up. A single
command imports every minute between an instrument's last stored bar and the last completed UTC
minute, writes only validated candles into `data/axe-trader.sqlite`, and reports the invalid source
data it rejected.

The existing `probe` / `stage` / `promote` workflow rebuilds a dataset from nothing and replaces the
active database wholesale. That is the right tool for a rebuild and the wrong one for a routine
top-up: it cannot append, and promoting a second instrument would discard the first. This design adds
the incremental path without altering the rebuild path.

## Scope and boundaries

The active local SQLite database at `data/axe-trader.sqlite` is authoritative and may hold many
instruments simultaneously. All fetching, validation, audit and merging happen locally against
Capital. No D1, dashboard, or Wrangler involvement.

Out of scope: repairing gaps behind the cursor, validating MONITOR mode's own writes, and
per-instrument strategy profiles. Those remain separate work.

## Clean-data invariant

Unchanged from the rebuild design and reused verbatim. Only validated candles enter
`historical_price`: every bid/ask OHLC field finite and strictly positive, bid at most ask on open,
high, low and close, volume present and non-negative, and the timestamp unique for its source, epic
and resolution and on a whole UTC minute.

An invalid candle is never inserted. Its minute and failure reason are recorded in `price_exclusion`
against the run that saw it. Recognised market closures are reported separately and are never
silently converted into exclusions.

## Cursor and window

An instrument's cursor is derived from the data, not stored separately: it is
`MAX(snapshot_time_utc)` over `historical_price` for that `(source, epic, resolution)`. A derived
cursor cannot drift out of sync with the rows it describes, and it makes an interrupted run
self-healing — the next run simply recomputes a shorter remaining window.

For each target the window is half-open `[cursor + 1 minute, now truncated to the minute)`. The
in-progress minute is excluded: it is partial, and storing it would freeze an incomplete candle that
the strict-append design would never revisit. When the window is empty the run reports the instrument
as already current and makes no network call.

An instrument with no stored rows has no cursor. It requires an explicit configured `from` to seed
it; without one the run fails closed rather than guessing a start date.

## Target resolution

`update` with no epic refreshes every distinct `(source, epic, resolution)` already present in
`historical_price`, each from its own cursor. An explicit epic scopes the run to that instrument and
is also how a new instrument is seeded. An explicit epic without a resolution refreshes every
resolution stored for that epic; seeding a new instrument requires both an explicit resolution and an
explicit `from`, since neither can be derived. Nothing about the instrument is hardcoded; the dataset
itself is the instrument list.

## Update flow

1. Resolve the target list and compute each target's window. Skip targets that are already current.
2. Stage the delta into a fresh per-run staging database under `data/.staging/`, reusing the existing
   staging store: page provenance, request pacing, the resumable work table, and the exclusion ledger
   all apply unchanged.
3. Audit the staged delta with the existing audit, which reports requested and actual bounds,
   received/accepted/rejected counts, duplicates, exclusions by reason, recognised closures and
   unexplained continuity gaps.
4. On a passing audit, merge the delta into the active database inside one transaction, using
   `INSERT OR IGNORE` into `historical_price`, `price_exclusion`, `history_import_page` and
   `history_import_run`. Delete the staging file only after the transaction commits.
5. Emit a per-instrument summary and a final rollup.

Dirty source data does not block a merge. Excluded candles are recorded and reported; the clean
candles from the same window still land. Only structural failures abort a merge: duplicates, counts
that fail to reconcile, or an unexplained continuity gap inside a non-empty provider page. This
matches the existing promotion gate, and it is what allows the top-up to run unattended — crossed
bid/ask is routine provider noise, currently about 0.18% of stored minutes.

Weekend and holiday top-ups are the ordinary case. Empty and 404 provider windows are already
classified as recognised closures; they advance the cursor and do not block.

## Reporting

Each instrument's run summary states the window, received, accepted and excluded counts, exclusions
by reason, recognised closures, continuity gaps, rows merged, and the resulting cursor.

A separate `report` mode reads the database only and makes no network call. Given an optional epic,
resolution and range, it reports stored minutes, excluded minutes, the resulting dirty rate,
a breakdown by failure reason, and per-run attribution — so historical data quality can be inspected
at any time without re-importing.

## Archive handling

`DatabaseBootstrap` restores a fresh checkout from `data/axe-trader.sqlite.gz`, so accumulated
top-ups make that snapshot progressively stale. Regenerating an 80 MB archive from a 281 MB database
on every routine append is disproportionate, so archive refresh is an explicit `archive` mode run
before committing the snapshot rather than a step in `update`.

## Operational safety

The active database is read-only until an audit passes. A provider rate limit, network failure or
audit failure aborts that instrument with the active database untouched and the staging file retained
as evidence; a rerun resumes from the recomputed cursor. A failure during the merge rolls the
transaction back, leaving the active database unchanged.

Repeating a run is always safe: `INSERT OR IGNORE` against the unique index on
`(source, epic, resolution, snapshot_time_utc)` makes the merge idempotent.

One instrument's failure does not abort the others in the same run, but the process exits non-zero if
any target failed. The command stays disabled during normal startup, monitoring and backtesting, and
runs only under an explicit enabling property.

## Tests and verification

Automated tests cover cursor resolution over empty, single-instrument, multi-instrument and
multi-resolution tables; window boundary math, including exclusion of the in-progress minute and an
empty window causing no network call; seeding failing closed without a cursor or configured `from`;
merge idempotency, where a repeated run leaves row counts identical; merge transactionality, where an
injected mid-merge failure leaves the active database byte-identical; the dirty-data policy, where
excluded candles merge but an unexplained continuity gap aborts; multi-instrument isolation, where
updating one instrument alters no row of another; and report-mode aggregation against a seeded
database.

The existing history and import tests and the full suite must remain green, with
`BacktestRunnerIntrabarTest` passing unchanged as the intrabar regression gate.

Operational verification runs `update` against the current four-day US500 gap, confirms the reported
window and dirty counts, verifies the new row count and bounds, reruns `update` to confirm it reports
already current and writes nothing, and runs a local backtest against the extended dataset.

## Precondition

This design extends code that currently exists only on the unmerged, unpushed
`feature/clean-local-price-history` branch. That branch must land on `main`, and the rebuilt gzip
snapshot must be committed, before this work has a trunk to build on.
