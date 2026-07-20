export interface RunRow {
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

export interface TradeRow {
  id: string;
  run_id: string;
  signal_key: string;
  entry_time: string | null;
  exit_time: string | null;
  direction: string | null;
  entry_price: number | null;
  exit_price: number | null;
  stop_price: number | null;
  target_price: number | null;
  pnl: number | null;
  net_pnl: number | null;
  r_multiple: number | null;
  exit_reason: string | null;
  is_win: number | null;
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
  bars_json: string | null;
}

export interface FeedbackRow {
  id: string;
  signal_key: string;
  flag: string | null;
  note: string | null;
  created_at: string;
}

export interface MarkRow {
  id: string;
  signal_key: string;
  kind: string;
  bar_ts: string;
  created_at: string;
}
