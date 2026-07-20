-- Human marks pinned to a specific bar: "T1 should have been here".
-- Keyed on signal_key + bar timestamp so they survive re-running the backtest.
CREATE TABLE marks (
  id          TEXT PRIMARY KEY,
  signal_key  TEXT NOT NULL,
  kind        TEXT NOT NULL,
  bar_ts      TEXT NOT NULL,
  created_at  TEXT NOT NULL,
  UNIQUE (signal_key, kind)
);
CREATE INDEX idx_marks_signal ON marks(signal_key);
