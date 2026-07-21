import type { TradeRow } from "../src/schema";

export async function seedRun(
  db: D1Database,
  overrides: Record<string, unknown> = {}
): Promise<string> {
  const id = (overrides.id as string) ?? `run-${crypto.randomUUID()}`;
  await db
    .prepare(
      `INSERT INTO runs (id, created_at, label, config_json, instrument, timeframe_min,
      window_start, window_end, trades_count, trades_per_day, win_rate, net_avg_pnl,
      net_avg_pnl_usd, avg_r, max_drawdown, worst_quarter_net)
     VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`
    )
    .bind(
      id,
      (overrides.created_at as string) ?? "2026-07-19T00:00:00Z",
      (overrides.label as string) ?? "test-run",
      (overrides.config_json as string) ?? "{}",
      "US500",
      5,
      "2025-01-01T00:00:00Z",
      "2025-12-31T00:00:00Z",
      (overrides.trades_count as number) ?? 0,
      0.9,
      (overrides.win_rate as number) ?? 0.8,
      (overrides.net_avg_pnl as number) ?? -0.14,
      -0.13,
      0.1,
      5.0,
      -1.5
    )
    .run();
  return id;
}

export async function seedTrade(
  db: D1Database,
  runId: string,
  overrides: Partial<TradeRow> = {}
): Promise<string> {
  const id = overrides.id ?? `trade-${crypto.randomUUID()}`;
  const signal = overrides.signal_key ?? `US500|2025-03-04T14:30:00Z|LONG`;
  await db
    .prepare(
      `INSERT INTO trades (id, run_id, signal_key, entry_time, exit_time, direction,
      entry_price, exit_price, stop_price, target_price, pnl, net_pnl, r_multiple,
      exit_reason, is_win, rsi_value, dist_to_trend_ema_atr, atr_value, atr_percentile,
      volume_ratio, hour_utc, day_of_week, volatility_regime, confluence_score,
      pillars_fired, bars_json)
     VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`
    )
    .bind(
      id,
      runId,
      signal,
      overrides.entry_time ?? "2025-03-04T14:30:00Z",
      overrides.exit_time ?? "2025-03-04T15:30:00Z",
      overrides.direction ?? "LONG",
      overrides.entry_price ?? 5000,
      overrides.exit_price ?? 5004,
      overrides.stop_price ?? 4988,
      overrides.target_price ?? 5003,
      overrides.pnl ?? 4,
      overrides.net_pnl ?? 3.5,
      overrides.r_multiple ?? 0.3,
      overrides.exit_reason ?? "TARGET",
      overrides.is_win ?? 1,
      overrides.rsi_value ?? 28,
      overrides.dist_to_trend_ema_atr ?? 1.2,
      overrides.atr_value ?? 4,
      overrides.atr_percentile ?? 0.6,
      overrides.volume_ratio ?? 1.0,
      overrides.hour_utc ?? 14,
      overrides.day_of_week ?? 2,
      overrides.volatility_regime ?? "NORMAL",
      overrides.confluence_score ?? 3,
      overrides.pillars_fired ?? "RSI+BB+S/R",
      overrides.bars_json ?? "[]"
    )
    .run();
  return id;
}
