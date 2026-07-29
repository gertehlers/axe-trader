CREATE TABLE discovery_runs (
  id TEXT PRIMARY KEY,
  instrument TEXT NOT NULL,
  timeframe_minutes INTEGER NOT NULL,
  window_from TEXT NOT NULL,
  window_to TEXT NOT NULL,
  promoted_candidate TEXT NOT NULL,
  total_net REAL NOT NULL,
  maximum_drawdown REAL NOT NULL,
  net_to_maximum_drawdown REAL NOT NULL,
  profitable_sampled_month_percentage REAL NOT NULL,
  directions_json TEXT NOT NULL
);

CREATE TABLE discovery_patterns (
  run_id TEXT NOT NULL REFERENCES discovery_runs(id),
  id TEXT NOT NULL,
  direction TEXT NOT NULL,
  clauses_json TEXT NOT NULL,
  independent_zones INTEGER NOT NULL,
  derivation_from TEXT NOT NULL,
  derivation_to TEXT NOT NULL,
  exit_policy_json TEXT NOT NULL,
  PRIMARY KEY (run_id, id)
);

CREATE TABLE discovery_months (
  run_id TEXT NOT NULL REFERENCES discovery_runs(id),
  candidate_id TEXT NOT NULL,
  month TEXT NOT NULL,
  trade_count INTEGER NOT NULL,
  sampled INTEGER NOT NULL,
  net_pnl REAL NOT NULL,
  PRIMARY KEY (run_id, candidate_id, month)
);

CREATE TABLE discovery_examples (
  run_id TEXT NOT NULL REFERENCES discovery_runs(id),
  kind TEXT NOT NULL,
  observable_features_json TEXT NOT NULL,
  pillar_transitions_json TEXT NOT NULL,
  oracle_result_json TEXT NOT NULL,
  executable_result_json TEXT NOT NULL,
  rule_clauses_json TEXT NOT NULL,
  chart_window_json TEXT NOT NULL,
  PRIMARY KEY (run_id, kind)
);
