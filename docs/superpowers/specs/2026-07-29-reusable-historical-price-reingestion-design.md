# Reusable Historical Price Re-ingestion Design

## Goal

Provide a one-off, reusable Capital.com historical-price importer that rebuilds a selected instrument and
resolution into a staged SQLite database, audits the result before it can replace the active history, and
preserves enough provenance to reproduce or reject the import later.

The immediate use is a development-only US500 minute-history rebuild for
`2024-12-04T23:20:00Z` through `2026-01-01T00:00:00Z`. It must not read the protected January–May
2026 OOS window.

## Scope and safety boundary

The importer is deliberately separate from application startup, monitoring, and trading. It never changes
the current `data/axe-trader.sqlite` during download or audit. It first creates a staging database, and
only an explicit successful promotion operation may replace the active database.

The command accepts these parameters:

- `epic` (required), allowing later imports for other instruments;
- `resolution` (required); 
- half-open `from` / `to` timestamps (required);
- destination staging database path (required or generated deterministically from the import arguments);
- optional source label, defaulting to `capital`.

The importer paginates the existing Capital REST historical-prices endpoint in requests of at most 1,000
bars, with authenticated sessions refreshed as needed. It maps the provider's bid/ask OHLC values verbatim;
it must not synthesize a spread, midpoint, or replacement quote.

## Data flow

1. Validate arguments, including a dedicated discovery-window guard when the caller requests a discovery
   import. The guard rejects any interval intersecting the protected OOS range before an API call.
2. Fetch the requested half-open interval page by page, retaining each raw page's request bounds and a
   SHA-256 payload hash in import metadata.
3. Write received bars and provenance into the staging SQLite database in idempotent timestamp order.
   The staging schema enforces uniqueness on source, epic, resolution, and timestamp.
4. Audit the completed staging data. The audit reports total and distinct timestamp counts, interval bounds,
   duplicate count, non-finite/non-positive field count, timestamp continuity/session-gap summary, and each
   bid/ask OHLC crossed-field count.
5. Refuse promotion unless the audit meets the configured policy. For the immediate US500 discovery import,
   zero non-positive/non-finite fields, zero duplicates, complete requested coverage outside recognised
   session closures, and zero crossed bid/ask fields are required.
6. If eligible, make a dated backup of the active database and atomically promote the staged database. The
   compressed snapshot is rebuilt only after the active replacement succeeds.

## Known-bad quote probe

Before a full import, the importer supports a narrow read-only probe for a timestamp range. The first probe
will cover `2025-01-20T16:21:00Z`, where the retained snapshot has a crossed close. It records the raw
provider result and audit result but performs no active-database write.

If Capital returns a crossed quote for that interval, the importer stops before a full replacement. That is
evidence that the provider's historical OHLC semantics conflict with the current strict executable-quote
invariant; importing it again would not make discovery trustworthy. Resolving that requires an explicit
policy decision, rather than silently relaxing the invariant.

## Test and verification strategy

Tests cover request pagination, half-open bounds, duplicate protection, mapping preservation, protected-OOS
rejection before network access, crossed-quote audit failure, and atomic promotion only after a clean audit.

The operational verification sequence is: run the known-bad probe; run the staged development-only import
only if the probe is clean; inspect the audit; promote only if the audit passes; then run the existing
development-only discovery harness and its deterministic-regeneration checks. No final OOS validation is
run.
