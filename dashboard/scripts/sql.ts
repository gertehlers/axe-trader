type Bar = { t: number; o: number; h: number; l: number; c: number };
type Trade = Record<string, unknown> & { signal_key: string; bars: Bar[] };
type RunFile = { run: Record<string, unknown> & { id: string }; trades: Trade[] };

function q(v: unknown): string {
  if (v === null || v === undefined) return "NULL";
  if (typeof v === "number") return String(v);
  return "'" + String(v).replace(/'/g, "''") + "'";
}

/**
 * Builds the SQL that loads one run.json into D1. Idempotent: re-pushing the same run id
 * replaces its rows rather than duplicating them. Trade ids are `${runId}:${signal_key}`,
 * so the same signal keeps a stable id within a run while feedback joins on signal_key alone.
 */
export function buildPushSql(file: RunFile): string {
  const r = file.run;
  const id = String(r.id);
  const lines: string[] = [];
  lines.push(`DELETE FROM trades WHERE run_id = ${q(id)};`);
  lines.push(`DELETE FROM runs WHERE id = ${q(id)};`);
  lines.push(
    `INSERT INTO runs (id, created_at, label, config_json, instrument, timeframe_min,
      window_start, window_end, trades_count, trades_per_day, win_rate, net_avg_pnl,
      net_avg_pnl_usd, avg_r, max_drawdown, worst_quarter_net) VALUES (` +
      [
        r.id,
        r.created_at,
        r.label,
        r.config_json,
        r.instrument,
        r.timeframe_min,
        r.window_start,
        r.window_end,
        r.trades_count,
        r.trades_per_day,
        r.win_rate,
        r.net_avg_pnl,
        r.net_avg_pnl_usd,
        r.avg_r,
        r.max_drawdown ?? null,
        r.worst_quarter_net ?? null,
      ]
        .map(q)
        .join(", ") +
      `);`
  );
  for (const t of file.trades) {
    lines.push(
      `INSERT INTO trades (id, run_id, signal_key, entry_time, exit_time, direction,
        entry_price, exit_price, stop_price, target_price, pnl, net_pnl, r_multiple,
        exit_reason, is_win, rsi_value, dist_to_trend_ema_atr, atr_value, atr_percentile,
        volume_ratio, hour_utc, day_of_week, volatility_regime, confluence_score,
        pillars_fired, bars_json) VALUES (` +
        [
          `${id}:${t.signal_key}`,
          id,
          t.signal_key,
          t.entry_time,
          t.exit_time,
          t.direction,
          t.entry_price,
          t.exit_price,
          t.stop_price,
          t.target_price,
          t.pnl,
          t.net_pnl,
          t.r_multiple,
          t.exit_reason,
          t.is_win,
          t.rsi_value,
          t.dist_to_trend_ema_atr,
          t.atr_value,
          t.atr_percentile,
          t.volume_ratio,
          t.hour_utc,
          t.day_of_week,
          t.volatility_regime,
          t.confluence_score,
          t.pillars_fired,
          JSON.stringify(t.bars),
        ]
          .map(q)
          .join(", ") +
        `);`
    );
  }
  return lines.join("\n");
}
