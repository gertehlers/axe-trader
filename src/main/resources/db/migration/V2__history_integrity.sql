-- One canonical timestamp text and one row per (source, epic, resolution, minute).
-- The import ledger tables (history_import_*, price_exclusion) are deliberately NOT created here:
-- the ledger is optional for the application and owned by the importer (HistoryDeltaMerger creates it).

UPDATE historical_price
SET snapshot_time_utc = substr(snapshot_time_utc, 1, 16) || ':00Z'
WHERE length(snapshot_time_utc) = 17 AND substr(snapshot_time_utc, 17, 1) = 'Z';

CREATE UNIQUE INDEX IF NOT EXISTS historical_price_source_epic_resolution_timestamp
ON historical_price (source, epic, resolution, snapshot_time_utc);
