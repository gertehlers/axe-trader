-- Trade-review dashboard schema.
-- runs   : one row per backtest/sweep config that was exported
-- trades : one row per trade of a run; bars_json inlines the chart window
-- feedback: phone-entered verdicts, keyed on signal_key so they survive re-runs

CREATE TABLE runs (
  id              TEXT PRIMARY KEY,
  created_at      TEXT NOT NULL,
  label           TEXT,
  config_json     TEXT,
  instrument      TEXT,
  timeframe_min   INTEGER,
  window_start    TEXT,
  window_end      TEXT,
  trades_count    INTEGER,
  trades_per_day  REAL,
  win_rate        REAL,
  net_avg_pnl     REAL,
  net_avg_pnl_usd REAL,
  avg_r           REAL,
  max_drawdown    REAL,
  worst_quarter_net REAL
);

CREATE TABLE trades (
  id             TEXT PRIMARY KEY,
  run_id         TEXT NOT NULL REFERENCES runs(id),
  signal_key     TEXT NOT NULL,
  entry_time     TEXT,
  exit_time      TEXT,
  direction      TEXT,
  entry_price    REAL,
  exit_price     REAL,
  stop_price     REAL,
  target_price   REAL,
  pnl            REAL,
  net_pnl        REAL,
  r_multiple     REAL,
  exit_reason    TEXT,
  is_win         INTEGER,
  rsi_value      REAL,
  dist_to_trend_ema_atr REAL,
  atr_value      REAL,
  atr_percentile REAL,
  volume_ratio   REAL,
  hour_utc       INTEGER,
  day_of_week    INTEGER,
  volatility_regime TEXT,
  confluence_score  INTEGER,
  pillars_fired  TEXT,
  bars_json      TEXT
);
CREATE INDEX idx_trades_run ON trades(run_id);
CREATE INDEX idx_trades_signal ON trades(signal_key);

CREATE TABLE feedback (
  id           TEXT PRIMARY KEY,
  signal_key   TEXT NOT NULL UNIQUE,
  flag         TEXT,
  note         TEXT,
  created_at   TEXT NOT NULL
);
