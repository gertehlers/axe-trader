import { describe, expect, it } from "vitest";
import { buildDiscoveryPushSql } from "../scripts/discovery-sql";

const report = {
  run: { run_key: "discovery-1", instrument: "US500", timeframe_minutes: 5, window_from: "a", window_to: "b", promoted_candidate: "candidate-a", total_net: 12.5, maximum_drawdown: 4, net_to_maximum_drawdown: 3.125, profitable_sampled_month_percentage: 66.7 },
  directions: { LONG: { total_net: 12.5, trade_count: 4 } },
  patterns: [{ id: "candidate-a", direction: "LONG", clauses: [{ feature: "rsi's" }], independent_zones: 4, derivation_from: "a", derivation_to: "b", exit_policy: {} }],
  monthly_results: [{ candidate_id: "candidate-a", month: "2025-02", trade_count: 12, sampled: true, net_pnl: 3.5 }],
  examples: [{ kind: "best", observable_features: {}, pillar_transitions: {}, oracle_result: {}, executable_result: {}, rule_clauses: [], chart_window: [{ close: 100 }] }],
};

describe("buildDiscoveryPushSql", () => {
  it("replaces child rows before the run, then inserts the report without duplicate-prone upserts", () => {
    const sql = buildDiscoveryPushSql(report);
    expect(sql).toContain("DELETE FROM discovery_examples WHERE run_id = 'discovery-1'");
    expect(sql).toContain("DELETE FROM discovery_months WHERE run_id = 'discovery-1'");
    expect(sql).toContain("DELETE FROM discovery_patterns WHERE run_id = 'discovery-1'");
    expect(sql).toContain("DELETE FROM discovery_runs WHERE id = 'discovery-1'");
    expect(sql.indexOf("discovery_examples")).toBeLessThan(sql.indexOf("INSERT INTO discovery_runs"));
  });

  it("escapes report text and round-trips nested JSON", () => {
    const sql = buildDiscoveryPushSql(report);
    expect(sql).toContain("rsi''s");
    expect(sql).toContain('[{"close":100}]');
  });

  it("rejects a report missing required run metadata before generating destructive SQL", () => {
    const incomplete = { ...report, run: { ...report.run, total_net: undefined } };
    expect(() => buildDiscoveryPushSql(incomplete)).toThrow(/total_net/);
  });
});
