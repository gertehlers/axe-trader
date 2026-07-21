import { useEffect, useState } from "react";
import * as api from "../api";
import type { Run, Slices, TradeSummary } from "../types";

const W = 320;
const H = 90;

function Kpi({ label, value }: { label: string; value: string }) {
  return (
    <div className="kpi">
      <span>{label}</span>
      <b>{value}</b>
    </div>
  );
}

export default function Overview({ run }: { run: Run }) {
  const [trades, setTrades] = useState<TradeSummary[]>([]);
  const [slices, setSlices] = useState<Slices | null>(null);

  useEffect(() => {
    let live = true;
    api
      .getTrades(run.id, "all")
      .then((rows) => live && setTrades(rows))
      .catch(() => live && setTrades([]));
    api
      .getSlices(run.id, "dist_to_trend_ema_atr", 4)
      .then((s) => live && setSlices(s))
      .catch(() => live && setSlices(null));
    return () => {
      live = false;
    };
  }, [run.id]);

  // Equity curve: cumulative net pnl in entry order.
  const ordered = [...trades].sort((a, b) => a.entry_time.localeCompare(b.entry_time));
  let cum = 0;
  const cumulative = ordered.map((t) => (cum += t.net_pnl));
  const lo = Math.min(0, ...cumulative);
  const hi = Math.max(0, ...cumulative);
  const points = cumulative
    .map((v, i) => {
      const x = (i / Math.max(1, cumulative.length - 1)) * W;
      const y = H - ((v - lo) / (hi - lo || 1)) * H;
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(" ");

  const pct = (v: number | null) => (v === null ? "—" : `${Math.round(v * 100)}%`);
  const signed = (v: number | null) => (v === null ? "—" : `${v >= 0 ? "+" : ""}${v.toFixed(2)}`);

  return (
    <section className="overview">
      <h2>{run.label ?? run.id}</h2>

      <div className="kpis">
        <Kpi label="win rate" value={pct(run.win_rate)} />
        <Kpi label="net pts/trade" value={signed(run.net_avg_pnl)} />
        <Kpi label="net $/trade" value={signed(run.net_avg_pnl_usd)} />
        <Kpi label="trades/day" value={run.trades_per_day?.toFixed(1) ?? "—"} />
        <Kpi label="avg R" value={signed(run.avg_r)} />
        <Kpi label="trades" value={String(run.trades_count ?? trades.length)} />
      </div>

      <h3>Equity (cumulative net pts)</h3>
      <svg viewBox={`0 0 ${W} ${H}`} width="100%" role="img" aria-label="equity curve">
        <polyline data-testid="equity-curve" points={points} className="equity" fill="none" />
      </svg>

      <h3>Win% by distance to trend EMA</h3>
      {slices && slices.buckets.length > 0 ? (
        <svg viewBox={`0 0 ${W} ${H}`} width="100%" role="img" aria-label="win rate by bucket">
          <line
            x1={0}
            x2={W}
            y1={H - slices.baseline_win * H}
            y2={H - slices.baseline_win * H}
            className="baseline"
          />
          {slices.buckets.map((b, i) => {
            const bw = W / slices.buckets.length;
            const h = b.win_pct * H;
            const winPct = Math.round(b.win_pct * 100);
            return (
              <g key={i}>
                <rect
                  data-testid="slice-bar"
                  x={i * bw + 4}
                  width={bw - 8}
                  y={H - h}
                  height={h}
                  className={b.win_pct >= slices.baseline_win ? "bucket good" : "bucket bad"}
                >
                  <title>
                    {`${b.lo.toFixed(2)}–${b.hi.toFixed(2)}: ${winPct}% win (n=${b.count})`}
                  </title>
                </rect>
                <text x={i * bw + bw / 2} y={H - 2} textAnchor="middle" className="bucket-count">
                  {`n=${b.count}`}
                </text>
              </g>
            );
          })}
        </svg>
      ) : (
        <p className="empty">No slice data.</p>
      )}
    </section>
  );
}
