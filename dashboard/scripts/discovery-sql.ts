type Report = {
  run: Record<string, unknown> & { run_key: string };
  directions: Record<string, unknown>;
  patterns: Record<string, unknown>[];
  monthly_results: Record<string, unknown>[];
  examples: Record<string, unknown>[];
};

function q(value: unknown): string {
  if (value === null || value === undefined) return "NULL";
  if (typeof value === "number") {
    if (!Number.isFinite(value)) throw new Error("report contains a non-finite number");
    return String(value);
  }
  return `'${String(value).replace(/'/g, "''")}'`;
}
const json = (value: unknown) => JSON.stringify(value);

export function buildDiscoveryPushSql(report: Report): string {
  const r = report.run;
  for (const field of [
    "run_key", "instrument", "timeframe_minutes", "window_from", "window_to", "promoted_candidate",
    "total_net", "maximum_drawdown", "net_to_maximum_drawdown", "profitable_sampled_month_percentage",
  ]) {
    if (r[field] === null || r[field] === undefined) throw new Error(`report missing required field: ${field}`);
  }
  const id = r.run_key;
  const lines = [
    "BEGIN;",
    `DELETE FROM discovery_examples WHERE run_id = ${q(id)};`,
    `DELETE FROM discovery_months WHERE run_id = ${q(id)};`,
    `DELETE FROM discovery_patterns WHERE run_id = ${q(id)};`,
    `DELETE FROM discovery_runs WHERE id = ${q(id)};`,
    `INSERT INTO discovery_runs (id, instrument, timeframe_minutes, window_from, window_to, promoted_candidate, total_net, maximum_drawdown, net_to_maximum_drawdown, profitable_sampled_month_percentage, directions_json) VALUES (${[
      id, r.instrument, r.timeframe_minutes, r.window_from, r.window_to, r.promoted_candidate, r.total_net,
      r.maximum_drawdown, r.net_to_maximum_drawdown, r.profitable_sampled_month_percentage, json(report.directions),
    ].map(q).join(", ")});`,
  ];
  for (const pattern of report.patterns) lines.push(`INSERT INTO discovery_patterns (run_id, id, direction, clauses_json, independent_zones, derivation_from, derivation_to, exit_policy_json) VALUES (${[
    id, pattern.id, pattern.direction, json(pattern.clauses), pattern.independent_zones, pattern.derivation_from, pattern.derivation_to, json(pattern.exit_policy),
  ].map(q).join(", ")});`);
  for (const month of report.monthly_results) lines.push(`INSERT INTO discovery_months (run_id, candidate_id, month, trade_count, sampled, net_pnl) VALUES (${[
    id, month.candidate_id, month.month, month.trade_count, month.sampled ? 1 : 0, month.net_pnl,
  ].map(q).join(", ")});`);
  for (const example of report.examples) lines.push(`INSERT INTO discovery_examples (run_id, kind, observable_features_json, pillar_transitions_json, oracle_result_json, executable_result_json, rule_clauses_json, chart_window_json) VALUES (${[
    id, example.kind, json(example.observable_features), json(example.pillar_transitions), json(example.oracle_result), json(example.executable_result), json(example.rule_clauses), json(example.chart_window),
  ].map(q).join(", ")});`);
  lines.push("COMMIT;");
  return lines.join("\n");
}
