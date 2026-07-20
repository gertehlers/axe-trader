import { Hono } from "hono";
import { runsRoutes } from "./routes/runs";

export type Env = { DB: D1Database };

const app = new Hono<{ Bindings: Env }>();

app.get("/api/health", (c) => c.json({ ok: true }));
app.route("/api", runsRoutes);

export default app;
