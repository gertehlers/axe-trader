import { Hono } from "hono";
import type { Env } from "../index";

// Allowlist — prevents SQL injection via the feature name (it goes into the SELECT list).
const FEATURES = new Set([
  "rsi_value",
  "dist_to_trend_ema_atr",
  "atr_value",
  "atr_percentile",
  "volume_ratio",
  "hour_utc",
  "day_of_week",
  "r_multiple",
]);

export const slicesRoutes = new Hono<{ Bindings: Env }>();

slicesRoutes.get("/runs/:id/slices", async (c) => {
  const feature = c.req.query("feature") ?? "";
  if (!FEATURES.has(feature)) return c.json({ error: "unknown feature" }, 400);
  const buckets = Math.max(1, Math.min(10, Number(c.req.query("buckets") ?? 5)));

  const { results } = await c.env.DB.prepare(
    `SELECT ${feature} AS v, is_win, net_pnl FROM trades
     WHERE run_id = ? AND ${feature} IS NOT NULL ORDER BY v`
  )
    .bind(c.req.param("id"))
    .all<{ v: number; is_win: number; net_pnl: number }>();

  const n = results.length;
  if (n === 0) return c.json({ baseline_win: 0, trades: 0, buckets: [] });
  const baselineWin = results.reduce((s, r) => s + r.is_win, 0) / n;

  const per = Math.max(1, Math.floor(n / buckets));
  const out = [];
  for (let start = 0; start < n; start += per) {
    const chunk = results.slice(start, start + per);
    if (chunk.length === 0) continue;
    out.push({
      lo: chunk[0].v,
      hi: chunk[chunk.length - 1].v,
      count: chunk.length,
      win_pct: chunk.reduce((s, r) => s + r.is_win, 0) / chunk.length,
      net_avg_pnl: chunk.reduce((s, r) => s + r.net_pnl, 0) / chunk.length,
    });
  }
  return c.json({ baseline_win: baselineWin, trades: n, buckets: out });
});
