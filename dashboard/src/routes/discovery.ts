import { Hono } from "hono";
import type { Env } from "../index";

type DiscoveryRunRow = {
  id: string; instrument: string; timeframe_minutes: number; window_from: string; window_to: string;
  promoted_candidate: string; total_net: number; maximum_drawdown: number; net_to_maximum_drawdown: number;
  profitable_sampled_month_percentage: number; directions_json: string;
};

const parse = (value: string) => JSON.parse(value) as unknown;
const compact = ({ directions_json: _directions, ...row }: DiscoveryRunRow) => row;

export const discoveryRoutes = new Hono<{ Bindings: Env }>();

discoveryRoutes.get("/discovery/runs", async (c) => {
  const { results } = await c.env.DB.prepare("SELECT * FROM discovery_runs ORDER BY window_to DESC, id DESC")
    .all<DiscoveryRunRow>();
  return c.json(results.map(compact));
});

discoveryRoutes.get("/discovery/runs/:id", async (c) => {
  const id = c.req.param("id");
  const run = await c.env.DB.prepare("SELECT * FROM discovery_runs WHERE id = ?").bind(id).first<DiscoveryRunRow>();
  if (!run) return c.json({ error: "not found" }, 404);
  const [patterns, months, examples] = await Promise.all([
    c.env.DB.prepare("SELECT * FROM discovery_patterns WHERE run_id = ? ORDER BY id").bind(id).all<any>(),
    c.env.DB.prepare("SELECT * FROM discovery_months WHERE run_id = ? ORDER BY month, candidate_id").bind(id).all<any>(),
    c.env.DB.prepare("SELECT * FROM discovery_examples WHERE run_id = ? ORDER BY kind").bind(id).all<any>(),
  ]);
  return c.json({
    run: compact(run), directions: parse(run.directions_json),
    patterns: patterns.results.map((row) => ({ ...row, clauses: parse(row.clauses_json), exit_policy: parse(row.exit_policy_json), clauses_json: undefined, exit_policy_json: undefined })),
    monthly_results: months.results.map((row) => ({ ...row, sampled: row.sampled === 1, run_id: undefined })),
    examples: examples.results.map((row) => ({
      kind: row.kind,
      observable_features: parse(row.observable_features_json), pillar_transitions: parse(row.pillar_transitions_json),
      oracle_result: parse(row.oracle_result_json), executable_result: parse(row.executable_result_json),
      rule_clauses: parse(row.rule_clauses_json), chart_window: parse(row.chart_window_json),
    })),
  });
});
