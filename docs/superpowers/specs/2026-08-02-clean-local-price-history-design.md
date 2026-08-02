# Clean Local Price History Design

## Goal

Rebuild the local US500 minute-price dataset from Capital as a clean, reproducible research source covering
`2024-01-01T00:00:00Z` through the time the import starts. The existing local SQLite database and committed
snapshot are legacy-corrupt data: they must not be extended or used as input to this rebuild.

## Scope and boundaries

The authoritative research dataset is local SQLite at `data/axe-trader.sqlite`. The import fetches directly
from Capital and performs all staging, validation, audit, and promotion locally. It makes no D1 calls or
writes. Dashboard strategy/OOS synchronisation is explicitly out of scope.

The importer is an explicit command, disabled during normal application startup, monitoring, and backtesting.
It accepts an instrument, resolution, half-open `from` and `to` timestamps, and a staging-database path. The
initial command uses `US500`, `MINUTE`, and the range from 2024-01-01 UTC through the recorded start instant.

## Clean-data invariant

Only validated Capital candles enter the active `historical_price` table. A candle is valid only when all of
the following hold:

- its timestamp is within the requested half-open interval and unique for its source, epic, and resolution;
- every bid/ask OHLC field is finite and strictly positive;
- bid is less than or equal to ask for open, high, low, and close.

An invalid candle is never inserted into clean price history. The importer records an exclusion for that
minute and its reason. The local database stores only minute candles; the strategy chooses how to roll them
into higher timeframes. Any derived bucket, at any selected timeframe, must be excluded when it contains an
excluded minute, so research never derives a bar from incomplete or invalid minute data. Recognised
market-closure gaps are reported separately and are not silently converted into exclusions.

## Import flow

1. Create a fresh staging SQLite database; do not read, copy, or append to the existing active database.
2. Authenticate once through the existing Capital client and fetch historical-price pages of at most 1,000
   candles, advancing with deterministic half-open UTC bounds until the requested end is reached.
3. Validate each received candle before insertion. Insert valid candles into staging history and rejected
   candles into the minute-level staging exclusion ledger in a transaction for each page. Persist page provenance: bounds,
   received count, accepted count, rejected count, and a response hash.
4. Audit the staged dataset. Report requested and actual bounds, distinct candle count, duplicate count,
   invalid/crossed-field counts, exclusion count and buckets, and continuity/session-gap summary.
5. Permit promotion only if the audit has no duplicate or malformed accepted candles and its exclusions are
   fully recorded. Rejected source bars are expected data-quality events, not a reason to corrupt the clean
   dataset; promotion must report them prominently.
6. On explicit promotion, make a dated local backup of the legacy database and gzip snapshot, atomically move
   the audited staging database to `data/axe-trader.sqlite`, then rebuild `data/axe-trader.sqlite.gz` from the
   promoted clean database.

## Operational safety

The new active database is not replaced during fetch or audit. Network failure, provider failure, paging
errors, and audit failure leave the current active files untouched. The command emits a durable audit report
and refuses promotion unless the staged import completed successfully. A future run can discard or replace
only its named staging file; it cannot modify D1.

## Tests and verification

Automated tests cover request pagination and half-open bounds, direct DTO mapping, clean-candle insertion,
invalid and crossed-candle rejection, excluded-minute recording, rejection of derived buckets at each
supported strategy timeframe when one contains an exclusion, source/timestamp uniqueness, audit reporting,
no active-database mutation before promotion, and atomic promotion with legacy backups.

Operational verification starts with a short read-only probe against Capital. Then run the full stage import,
inspect the audit and exclusions, promote explicitly, verify the local database bounds/counts and rebuilt gzip
snapshot, and run a local backtest that confirms no derived bucket containing an excluded minute is used. No
D1 command is run in this workflow.
