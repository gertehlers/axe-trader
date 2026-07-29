import type { Flag, MarkKind } from "../../shared/vocab";

export type Bar = { t: number; o: number; h: number; l: number; c: number };

export interface Run {
  id: string;
  created_at: string;
  label: string | null;
  config_json: string | null;
  instrument: string | null;
  timeframe_min: number | null;
  window_start: string | null;
  window_end: string | null;
  trades_count: number | null;
  trades_per_day: number | null;
  win_rate: number | null;
  net_avg_pnl: number | null;
  net_avg_pnl_usd: number | null;
  avg_r: number | null;
  max_drawdown: number | null;
  worst_quarter_net: number | null;
}

export interface TradeSummary {
  id: string;
  run_id: string;
  signal_key: string;
  entry_time: string;
  exit_time: string;
  direction: "LONG" | "SHORT";
  entry_price: number;
  exit_price: number;
  stop_price: number;
  target_price: number;
  pnl: number;
  net_pnl: number;
  r_multiple: number;
  exit_reason: string | null;
  is_win: number;
  rsi_value: number | null;
  dist_to_trend_ema_atr: number | null;
  atr_value: number | null;
  atr_percentile: number | null;
  volume_ratio: number | null;
  hour_utc: number | null;
  day_of_week: number | null;
  volatility_regime: string | null;
  confluence_score: number | null;
  pillars_fired: string | null;
}

export interface Trade extends TradeSummary {
  bars_json: string;
}

export interface Feedback {
  id: string;
  signal_key: string;
  flag: Flag | null;
  note: string | null;
  created_at: string;
}

export interface Mark {
  id: string;
  signal_key: string;
  kind: MarkKind;
  bar_ts: string;
  created_at: string;
}

export interface Slices {
  baseline_win: number;
  trades: number;
  buckets: { lo: number; hi: number; count: number; win_pct: number; net_avg_pnl: number }[];
}

export type TradeFilter = "all" | "losers" | "winners";

export interface DiscoveryRun {
  id: string; instrument: string; timeframe_minutes: number; window_from: string; window_to: string;
  promoted_candidate: string; total_net: number; maximum_drawdown: number; net_to_maximum_drawdown: number | "Infinity";
  profitable_sampled_month_percentage: number;
}
export interface DiscoveryReport {
  run: DiscoveryRun;
  directions: Record<string, { total_net: number; trade_count: number }>;
  patterns: { id: string; direction: string; clauses: { feature: string; operator: string; threshold: number }[]; independent_zones: number }[];
  monthly_results: { candidate_id: string; month: string; trade_count: number; sampled: boolean; net_pnl: number }[];
  examples: { kind: string; observable_features: Record<string, unknown>; pillar_transitions: Record<string, unknown>; rule_clauses: unknown[]; oracle_result: Record<string, unknown>; executable_result: Record<string, unknown>; chart_window: unknown[] }[];
}
