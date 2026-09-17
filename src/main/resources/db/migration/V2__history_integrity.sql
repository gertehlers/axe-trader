UPDATE historical_price
SET snapshot_time_utc = substr(snapshot_time_utc, 1, 16) || ':00Z'
WHERE length(snapshot_time_utc) = 17 AND substr(snapshot_time_utc, 17, 1) = 'Z';

CREATE UNIQUE INDEX IF NOT EXISTS historical_price_source_epic_resolution_timestamp
ON historical_price (source, epic, resolution, snapshot_time_utc);

CREATE TABLE IF NOT EXISTS history_import_run (
  import_run_id TEXT PRIMARY KEY, algorithm_version INTEGER NOT NULL, source TEXT NOT NULL,
  epic TEXT NOT NULL, resolution TEXT NOT NULL, requested_from_utc TEXT NOT NULL,
  requested_to_utc TEXT NOT NULL, created_at_utc TEXT NOT NULL);

CREATE TABLE IF NOT EXISTS price_exclusion (
  import_run_id TEXT NOT NULL, source TEXT NOT NULL, epic TEXT NOT NULL, resolution TEXT NOT NULL,
  snapshot_time_utc TEXT NOT NULL, reason TEXT NOT NULL, detected_at_utc TEXT NOT NULL,
  PRIMARY KEY (import_run_id, source, epic, resolution, snapshot_time_utc, reason),
  FOREIGN KEY (import_run_id) REFERENCES history_import_run(import_run_id));

CREATE INDEX IF NOT EXISTS price_exclusion_epic_resolution_timestamp
ON price_exclusion (epic, resolution, snapshot_time_utc);

CREATE TABLE IF NOT EXISTS history_import_page (
  import_run_id TEXT NOT NULL, requested_from_utc TEXT NOT NULL, requested_to_utc TEXT NOT NULL,
  payload_hash TEXT NOT NULL, received_count INTEGER NOT NULL, accepted_count INTEGER NOT NULL,
  rejected_count INTEGER NOT NULL,
  PRIMARY KEY (import_run_id, requested_from_utc, requested_to_utc),
  FOREIGN KEY (import_run_id) REFERENCES history_import_run(import_run_id));

CREATE TABLE IF NOT EXISTS history_import_closure (
  import_run_id TEXT NOT NULL, from_utc TEXT NOT NULL, to_utc TEXT NOT NULL,
  provenance TEXT NOT NULL, payload_hash TEXT NOT NULL,
  PRIMARY KEY (import_run_id, from_utc, to_utc),
  FOREIGN KEY (import_run_id) REFERENCES history_import_run(import_run_id));
