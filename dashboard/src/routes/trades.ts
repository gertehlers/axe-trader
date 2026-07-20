import { Hono } from "hono";
import type { Env } from "../index";
import type { TradeRow } from "../schema";

export const tradesRoutes = new Hono<{ Bindings: Env }>();

// Columns for the list view — everything EXCEPT bars_json (keep list light).
const LIST_COLS = `id, run_id, signal_key, entry_time, exit_time, direction, entry_price, exit_price,
   stop_price, target_price, pnl, net_pnl, r_multiple, exit_reason, is_win, rsi_value,
   dist_to_trend_ema_atr, atr_value, atr_percentile, volume_ratio, hour_utc, day_of_week,
   volatility_regime, confluence_score, pillars_fired`;

tradesRoutes.get("/runs/:id/trades", async (c) => {
  const filter = c.req.query("filter") ?? "all";
  let where = "run_id = ?";
  if (filter === "losers") where += " AND is_win = 0";
  else if (filter === "winners") where += " AND is_win = 1";
  const { results } = await c.env.DB.prepare(
    `SELECT ${LIST_COLS} FROM trades WHERE ${where} ORDER BY entry_time`
  )
    .bind(c.req.param("id"))
    .all<Omit<TradeRow, "bars_json">>();
  return c.json(results);
});

tradesRoutes.get("/trades/:id", async (c) => {
  const row = await c.env.DB.prepare("SELECT * FROM trades WHERE id = ?")
    .bind(c.req.param("id"))
    .first<TradeRow>();
  if (!row) return c.json({ error: "not found" }, 404);
  return c.json(row);
});
