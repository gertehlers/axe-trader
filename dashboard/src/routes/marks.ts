import { Hono } from "hono";
import type { Env } from "../index";
import type { MarkRow } from "../schema";
import { isMarkKind } from "../../shared/vocab";

export const marksRoutes = new Hono<{ Bindings: Env }>();

marksRoutes.get("/marks", async (c) => {
  const key = c.req.query("signal_key");
  const stmt = key
    ? c.env.DB.prepare("SELECT * FROM marks WHERE signal_key = ? ORDER BY kind").bind(key)
    : c.env.DB.prepare("SELECT * FROM marks ORDER BY signal_key, kind");
  const { results } = await stmt.all<MarkRow>();
  return c.json(results);
});

marksRoutes.post("/marks", async (c) => {
  const body = await c.req.json<{ signal_key?: string; kind?: string; bar_ts?: string }>();
  if (!body.signal_key || !body.bar_ts) return c.json({ error: "signal_key and bar_ts required" }, 400);
  if (!isMarkKind(body.kind)) return c.json({ error: "unknown kind" }, 400);

  // One mark per (signal_key, kind): re-placing a kind moves it rather than adding a second.
  await c.env.DB.prepare(
    `INSERT INTO marks (id, signal_key, kind, bar_ts, created_at)
     VALUES (?, ?, ?, ?, ?)
     ON CONFLICT(signal_key, kind) DO UPDATE SET
       bar_ts = excluded.bar_ts, created_at = excluded.created_at`
  )
    .bind(crypto.randomUUID(), body.signal_key, body.kind, body.bar_ts, new Date().toISOString())
    .run();

  const row = await c.env.DB.prepare("SELECT * FROM marks WHERE signal_key = ? AND kind = ?")
    .bind(body.signal_key, body.kind)
    .first<MarkRow>();
  return c.json(row);
});

marksRoutes.delete("/marks", async (c) => {
  const key = c.req.query("signal_key");
  const kind = c.req.query("kind");
  if (!key || !kind) return c.json({ error: "signal_key and kind required" }, 400);
  const res = await c.env.DB.prepare("DELETE FROM marks WHERE signal_key = ? AND kind = ?")
    .bind(key, kind)
    .run();
  return c.json({ deleted: res.meta.changes ?? 0 });
});
