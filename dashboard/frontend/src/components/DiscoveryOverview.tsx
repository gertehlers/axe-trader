import type { DiscoveryReport } from "../types";
const signed = (value: number) => `${value >= 0 ? "+" : ""}${value.toFixed(2)}`;
const Kpi = ({ label, value }: { label: string; value: string }) => <div className="kpi"><span>{label}</span><b>{value}</b></div>;
export default function DiscoveryOverview({ report }: { report: DiscoveryReport }) {
  const { run } = report;
  const months = run.promoted_candidate
    ? report.monthly_results.filter((month) => month.candidate_id === run.promoted_candidate)
    : report.monthly_results;
  return <section className="overview discovery-overview"><h2>{run.instrument} discovery</h2>
    {!run.promoted_candidate ? <p className="empty">No candidate passed the development gate.</p> : <>
      <div className="kpis"><Kpi label="total net" value={signed(run.total_net)} /><Kpi label="max drawdown" value={run.maximum_drawdown ? `−${run.maximum_drawdown.toFixed(2)}` : "0.00"} /><Kpi label="profitable months" value={`${Math.round(run.profitable_sampled_month_percentage)}%`} /><Kpi label="net / drawdown" value={run.net_to_maximum_drawdown === "Infinity" ? "∞" : run.net_to_maximum_drawdown.toFixed(2)} /></div>
      <h3>Directions</h3><div className="discovery-list">{Object.entries(report.directions).map(([d, value]) => <p key={d}>{d.toLowerCase()} {signed(value.total_net)} · n={value.trade_count}</p>)}</div>
    </>}
    <h3>Monthly results</h3><div className="discovery-list">{months.map((m) => <p key={`${m.candidate_id}:${m.month}`}>{m.month} {signed(m.net_pnl)} · n={m.trade_count}{m.sampled ? "" : " (below sample threshold)"}</p>)}</div>
    <h3>Rules</h3><div className="discovery-list">{report.patterns.map((p) => <div key={p.id}><p>{p.id} · {p.independent_zones} independent zones</p>{p.clauses.map((c, i) => <p key={i}>{c.feature} {c.operator} {c.threshold}</p>)}</div>)}</div>
    <h3>Examples</h3><div className="discovery-examples">{report.examples.map((e) => <article key={e.kind} className="card"><h4>{e.kind}</h4><p>oracle: {String(e.oracle_result.status ?? "—")}</p><p>capture: {typeof e.executable_result.capture_ratio === "number" ? e.executable_result.capture_ratio.toFixed(2) : "—"}</p><p>features: {JSON.stringify(e.observable_features)}</p><p>transitions: {JSON.stringify(e.pillar_transitions)}</p><p>clauses: {JSON.stringify(e.rule_clauses)}</p><pre>{JSON.stringify(e.chart_window, null, 2)}</pre></article>)}</div>
  </section>;
}
