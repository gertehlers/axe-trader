PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS discovery_run (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_key TEXT NOT NULL UNIQUE,
    input_data_sha256 TEXT NOT NULL,
    config_sha256 TEXT NOT NULL,
    feature_schema_version TEXT NOT NULL,
    score_version TEXT NOT NULL,
    source_commit TEXT NOT NULL,
    source_dirty INTEGER NOT NULL,
    instrument TEXT NOT NULL,
    timeframe_min INTEGER NOT NULL,
    window_from TEXT NOT NULL,
    window_to TEXT NOT NULL,
    CHECK (window_from < window_to)
);

CREATE TABLE IF NOT EXISTS observation (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id INTEGER NOT NULL REFERENCES discovery_run(id),
    instrument TEXT NOT NULL,
    timeframe_min INTEGER NOT NULL,
    signal_ts TEXT NOT NULL,
    direction TEXT NOT NULL,
    signal_index INTEGER NOT NULL,
    entry_index INTEGER NOT NULL,
    entry_atr REAL NOT NULL,
    minutes_to_trading_close INTEGER NOT NULL,
    features_json TEXT NOT NULL,
    UNIQUE(run_id, instrument, timeframe_min, signal_ts, direction)
);

CREATE TABLE IF NOT EXISTS observation_exclusion (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id INTEGER NOT NULL REFERENCES discovery_run(id),
    instrument TEXT NOT NULL,
    timeframe_min INTEGER NOT NULL,
    signal_ts TEXT NOT NULL,
    direction TEXT NOT NULL,
    signal_index INTEGER NOT NULL,
    reason TEXT NOT NULL,
    detail TEXT NOT NULL,
    UNIQUE(run_id, instrument, timeframe_min, signal_ts, direction)
);

CREATE TABLE IF NOT EXISTS forward_label (
    observation_id INTEGER PRIMARY KEY REFERENCES observation(id),
    status TEXT NOT NULL,
    entry_price REAL,
    entry_ts TEXT,
    exit_path_json TEXT NOT NULL,
    mfe_points REAL NOT NULL,
    mfe_atr REAL NOT NULL,
    mae_points REAL NOT NULL,
    mae_atr REAL NOT NULL,
    mae_before_mfe INTEGER NOT NULL,
    excursion_order TEXT NOT NULL,
    horizon_returns_points_json TEXT NOT NULL,
    horizon_returns_atr_json TEXT NOT NULL,
    directional_efficiency REAL NOT NULL,
    time_to_mfe_bars INTEGER NOT NULL,
    time_to_mae_bars INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS window_spent (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id INTEGER NOT NULL REFERENCES discovery_run(id),
    window_from TEXT NOT NULL,
    window_to TEXT NOT NULL,
    candidate_id TEXT NOT NULL,
    spent_at TEXT NOT NULL,
    UNIQUE(window_from, window_to),
    CHECK (window_from < window_to)
);

CREATE TABLE IF NOT EXISTS opportunity_zone (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id INTEGER NOT NULL REFERENCES discovery_run(id),
    direction TEXT NOT NULL,
    score_version TEXT NOT NULL,
    opened_at TEXT NOT NULL,
    closed_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS zone_member (
    zone_id INTEGER NOT NULL REFERENCES opportunity_zone(id),
    observation_id INTEGER NOT NULL REFERENCES observation(id),
    PRIMARY KEY (zone_id, observation_id)
);

CREATE TABLE IF NOT EXISTS pattern_family (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id INTEGER NOT NULL REFERENCES discovery_run(id),
    direction TEXT NOT NULL,
    definition_json TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS candidate_rule (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    pattern_family_id INTEGER NOT NULL REFERENCES pattern_family(id),
    definition_json TEXT NOT NULL,
    registered_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS exit_policy (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    candidate_rule_id INTEGER NOT NULL REFERENCES candidate_rule(id),
    definition_json TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS monthly_result (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    candidate_rule_id INTEGER NOT NULL REFERENCES candidate_rule(id),
    month TEXT NOT NULL,
    result_json TEXT NOT NULL,
    UNIQUE(candidate_rule_id, month)
);

CREATE TABLE IF NOT EXISTS validation_trade (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    candidate_rule_id INTEGER NOT NULL REFERENCES candidate_rule(id),
    entry_ts TEXT NOT NULL,
    exit_ts TEXT NOT NULL,
    result_json TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS experiment_event (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id INTEGER NOT NULL REFERENCES discovery_run(id),
    event_type TEXT NOT NULL,
    event_json TEXT NOT NULL,
    created_at TEXT NOT NULL
);
