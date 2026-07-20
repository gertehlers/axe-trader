import { Hono } from "hono";
import type { Env } from "../index";
import type { FeedbackRow } from "../schema";

export const feedbackRoutes = new Hono<{ Bindings: Env }>();

feedbackRoutes.get("/feedback", async (c) => {
  const key = c.req.query("signal_key");
  if (key) {
    const row = await c.env.DB.prepare("SELECT * FROM feedback WHERE signal_key = ?")
      .bind(key)
      .first<FeedbackRow>();
    if (!row) return c.json({ error: "not found" }, 404);
    return c.json(row);
  }
  const { results } = await c.env.DB.prepare(
    "SELECT * FROM feedback ORDER BY created_at DESC"
  ).all<FeedbackRow>();
  return c.json(results);
});

feedbackRoutes.post("/feedback", async (c) => {
  const body = await c.req.json<{ signal_key?: string; flag?: string; note?: string }>();
  if (!body.signal_key) return c.json({ error: "signal_key required" }, 400);
  const now = new Date().toISOString();
  // Keyed on signal_key, not trade id — a verdict must survive re-running the backtest.
  await c.env.DB.prepare(
    `INSERT INTO feedback (id, signal_key, flag, note, created_at)
     VALUES (?, ?, ?, ?, ?)
     ON CONFLICT(signal_key) DO UPDATE SET
       flag = excluded.flag, note = excluded.note, created_at = excluded.created_at`
  )
    .bind(crypto.randomUUID(), body.signal_key, body.flag ?? null, body.note ?? null, now)
    .run();
  const row = await c.env.DB.prepare("SELECT * FROM feedback WHERE signal_key = ?")
    .bind(body.signal_key)
    .first<FeedbackRow>();
  return c.json(row);
});
