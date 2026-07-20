import { Hono } from "hono";
import { runsRoutes } from "./routes/runs";
import { tradesRoutes } from "./routes/trades";
import { slicesRoutes } from "./routes/slices";
import { feedbackRoutes } from "./routes/feedback";
import { marksRoutes } from "./routes/marks";

export type Env = { DB: D1Database };

const app = new Hono<{ Bindings: Env }>();

app.get("/api/health", (c) => c.json({ ok: true }));
app.route("/api", runsRoutes);
app.route("/api", tradesRoutes);
app.route("/api", slicesRoutes);
app.route("/api", feedbackRoutes);
app.route("/api", marksRoutes);

export default app;
