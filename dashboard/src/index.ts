import { Hono } from "hono";
import { runsRoutes } from "./routes/runs";
import { tradesRoutes } from "./routes/trades";

export type Env = { DB: D1Database };

const app = new Hono<{ Bindings: Env }>();

app.get("/api/health", (c) => c.json({ ok: true }));
app.route("/api", runsRoutes);
app.route("/api", tradesRoutes);

export default app;
