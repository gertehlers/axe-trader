import { Hono } from "hono";
import type { Env } from "../index";
import type { RunRow } from "../schema";

export const runsRoutes = new Hono<{ Bindings: Env }>();

runsRoutes.get("/runs", async (c) => {
  const { results } = await c.env.DB.prepare(
    "SELECT * FROM runs ORDER BY created_at DESC"
  ).all<RunRow>();
  return c.json(results);
});

runsRoutes.get("/runs/:id", async (c) => {
  const row = await c.env.DB.prepare("SELECT * FROM runs WHERE id = ?")
    .bind(c.req.param("id"))
    .first<RunRow>();
  if (!row) return c.json({ error: "not found" }, 404);
  return c.json(row);
});
