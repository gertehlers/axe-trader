import { describe, it, expect } from "vitest";
import { buildPushSql } from "../scripts/sql";

const run = {
  run: {
    id: "US500-1",
    created_at: "2026-07-19T00:00:00Z",
    label: "l",
    config_json: "{}",
    instrument: "US500",
    timeframe_min: 5,
    window_start: "a",
    window_end: "b",
    trades_count: 1,
    trades_per_day: 0.9,
    win_rate: 0.8,
    net_avg_pnl: -0.14,
    net_avg_pnl_usd: -0.13,
    avg_r: 0.1,
  },
  trades: [
    {
      signal_key: "US500|2025-03-04T14:30:00Z|LONG",
      entry_time: "2025-03-04T14:30:00Z",
      exit_time: "2025-03-04T15:30:00Z",
      direction: "LONG",
      entry_price: 5000,
      exit_price: 5003,
      stop_price: 4988,
      target_price: 5003,
      pnl: 3,
      net_pnl: 3,
      r_multiple: 0.75,
      exit_reason: "TARGET",
      is_win: 1,
      rsi_value: 28,
      dist_to_trend_ema_atr: 1.2,
      atr_value: 4,
      atr_percentile: 0.6,
      volume_ratio: 1,
      hour_utc: 14,
      day_of_week: 2,
      volatility_regime: "NORMAL",
      confluence_score: 3,
      pillars_fired: "RSI+BB",
      bars: [{ t: 1, o: 1, h: 2, l: 0, c: 1 }],
    },
  ],
};

describe("buildPushSql", () => {
  it("is idempotent (deletes the run + its trades first)", () => {
    const sql = buildPushSql(run as never);
    expect(sql).toContain("DELETE FROM trades WHERE run_id = 'US500-1'");
    expect(sql).toContain("DELETE FROM runs WHERE id = 'US500-1'");
    expect(sql.indexOf("DELETE")).toBeLessThan(sql.indexOf("INSERT"));
  });

  it("escapes single quotes in text (e.g. config_json / pillars)", () => {
    const dirty = { ...run, run: { ...run.run, config_json: '{"a":"o\'brien"}' } };
    const sql = buildPushSql(dirty as never);
    expect(sql).toContain("o''brien");
  });

  it("stores the bar window as bars_json text", () => {
    const sql = buildPushSql(run as never);
    expect(sql).toContain('[{"t":1,"o":1,"h":2,"l":0,"c":1}]'.replace(/'/g, "''"));
  });
});
