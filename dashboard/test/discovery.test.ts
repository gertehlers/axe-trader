import { env, applyD1Migrations } from "cloudflare:test";
import { beforeAll, describe, expect, it } from "vitest";
import app from "../src/index";

beforeAll(async () => applyD1Migrations(env.DB, env.TEST_MIGRATIONS));

async function seed(id = "discovery-1") {
  await env.DB.prepare(`INSERT INTO discovery_runs
    (id, instrument, timeframe_minutes, window_from, window_to, promoted_candidate, total_net,
     maximum_drawdown, net_to_maximum_drawdown, profitable_sampled_month_percentage, directions_json)
    VALUES (?, 'US500', 5, '2025-01-01T00:00:00Z', '2025-12-31T00:00:00Z', 'candidate-a', 12.5, 4, 3.125, 66.7, ?)`)
    .bind(id, JSON.stringify({ LONG: { total_net: 8, trade_count: 4 } })).run();
  await env.DB.prepare(`INSERT INTO discovery_patterns
    (run_id, id, direction, clauses_json, independent_zones, derivation_from, derivation_to, exit_policy_json)
    VALUES (?, 'candidate-a', 'LONG', '[{"feature":"rsi"}]', 4, 'a', 'b', '{}')`).bind(id).run();
  await env.DB.prepare(`INSERT INTO discovery_months
    (run_id, candidate_id, month, trade_count, sampled, net_pnl)
    VALUES (?, 'candidate-a', '2025-02', 12, 1, 3.5)`).bind(id).run();
  await env.DB.prepare(`INSERT INTO discovery_examples
    (run_id, kind, observable_features_json, pillar_transitions_json, oracle_result_json,
     executable_result_json, rule_clauses_json, chart_window_json)
    VALUES (?, 'best', '{"rsi":30}', '{}', '{"status":"RUN"}', '{"net_pnl":2}', '[]', '[{"close":100}]')`)
    .bind(id).run();
}

describe("discovery API", () => {
  it("lists compact discovery runs without detail collections", async () => {
    await seed();
    const rows = await (await app.request("/api/discovery/runs", {}, env)).json() as Record<string, unknown>[];
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ id: "discovery-1", total_net: 12.5 });
    expect(rows[0]).not.toHaveProperty("examples");
  });

  it("returns a report with patterns, months, directions, and examples joined to its run", async () => {
    await seed("discovery-detail");
    const report = await (await app.request("/api/discovery/runs/discovery-detail", {}, env)).json() as Record<string, any>;
    expect(report.run.id).toBe("discovery-detail");
    expect(report.patterns[0]).toMatchObject({ id: "candidate-a", clauses: [{ feature: "rsi" }] });
    expect(report.monthly_results).toEqual([expect.objectContaining({ month: "2025-02", sampled: true })]);
    expect(report.directions).toEqual({ LONG: { total_net: 8, trade_count: 4 } });
    expect(report.examples[0]).toMatchObject({ kind: "best", chart_window: [{ close: 100 }] });
  });

  it("returns 404 for a missing discovery run", async () => {
    expect((await app.request("/api/discovery/runs/missing", {}, env)).status).toBe(404);
  });
});
