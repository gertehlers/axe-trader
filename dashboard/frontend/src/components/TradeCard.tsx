import type { MouseEvent } from "react";
import type { Bar, Mark, Trade } from "../types";
import { barIndexAtOrAfter, barIndexAtX, focusWindow, makeScales, priceRange } from "../chart";

export const VIEW_W = 320;
export const VIEW_H = 180;

export type Zoom = "focus" | "full";

interface Props {
  trade: Trade;
  bars: Bar[];
  marks: Mark[];
  zoom: Zoom;
  onZoomChange: (z: Zoom) => void;
  onBarTap: (barIso: string) => void;
}

const iso = (bar: Bar) => new Date(bar.t * 1000).toISOString();

export default function TradeCard({ trade, bars, marks, zoom, onZoomChange, onBarTap }: Props) {
  const win =
    zoom === "focus"
      ? focusWindow(bars, trade.entry_time, trade.exit_time)
      : { from: 0, to: Math.max(0, bars.length - 1) };
  const visible = bars.slice(win.from, win.to + 1);
  const { min, max } = priceRange(visible, [trade.stop_price, trade.target_price, trade.entry_price]);
  const s = makeScales({ count: visible.length, min, max, width: VIEW_W, height: VIEW_H });

  const localIndex = (i: number) => i - win.from;
  const entryIdx = localIndex(barIndexAtOrAfter(bars, trade.entry_time));
  const exitIdx = localIndex(barIndexAtOrAfter(bars, trade.exit_time));
  const inView = (i: number) => i >= 0 && i < visible.length;

  function handleClick(e: MouseEvent<SVGSVGElement>) {
    const box = e.currentTarget.getBoundingClientRect();
    const x = ((e.clientX - box.left) / box.width) * VIEW_W;
    const idx = barIndexAtX(x, visible.length, VIEW_W);
    onBarTap(iso(visible[idx]));
  }

  return (
    <div className="card">
      <header className="card-head">
        <span>
          {trade.direction} · {trade.entry_time.replace("T", " ").slice(0, 16)}Z
        </span>
        <span className={trade.net_pnl >= 0 ? "win" : "loss"}>
          {trade.net_pnl >= 0 ? "+" : ""}
          {trade.net_pnl.toFixed(2)} pts
        </span>
      </header>

      <svg
        data-testid="candles"
        viewBox={`0 0 ${VIEW_W} ${VIEW_H}`}
        width="100%"
        onClick={handleClick}
        role="img"
        aria-label="price chart"
      >
        <line
          data-testid="target-line"
          x1={0}
          x2={VIEW_W}
          y1={s.y(trade.target_price)}
          y2={s.y(trade.target_price)}
          className="level target"
        />
        <line
          data-testid="stop-line"
          x1={0}
          x2={VIEW_W}
          y1={s.y(trade.stop_price)}
          y2={s.y(trade.stop_price)}
          className="level stop"
        />

        {visible.map((b, i) => (
          <g key={b.t} data-testid="candle">
            <line x1={s.x(i)} x2={s.x(i)} y1={s.y(b.h)} y2={s.y(b.l)} className="wick" />
            <rect
              x={s.x(i) - s.barWidth / 2}
              width={s.barWidth}
              y={s.y(Math.max(b.o, b.c))}
              height={Math.max(1, Math.abs(s.y(b.o) - s.y(b.c)))}
              className={b.c >= b.o ? "body up" : "body down"}
            />
          </g>
        ))}

        {inView(entryIdx) && (
          <circle
            data-testid="entry-marker"
            cx={s.x(entryIdx)}
            cy={s.y(trade.entry_price)}
            r={4}
            className="entry"
          />
        )}
        {inView(exitIdx) && (
          <circle
            data-testid="exit-marker"
            cx={s.x(exitIdx)}
            cy={s.y(trade.exit_price)}
            r={4}
            className="exit"
          />
        )}

        {marks.map((m) => {
          const i = localIndex(barIndexAtOrAfter(bars, m.bar_ts));
          if (!inView(i)) return null;
          return (
            <g key={m.kind} data-testid={`mark-${m.kind}`}>
              <line x1={s.x(i)} x2={s.x(i)} y1={0} y2={VIEW_H} className={`mark ${m.kind}`} />
              <text x={s.x(i)} y={10} className="mark-label" textAnchor="middle">
                {m.kind}
              </text>
            </g>
          );
        })}
      </svg>

      <div className="zoom">
        <button aria-pressed={zoom === "focus"} onClick={() => onZoomChange("focus")}>
          Focus
        </button>
        <button aria-pressed={zoom === "full"} onClick={() => onZoomChange("full")}>
          Full
        </button>
      </div>

      <dl className="facts">
        <div>
          <dt>R</dt>
          <dd>{trade.r_multiple.toFixed(2)}</dd>
        </div>
        <div>
          <dt>exit</dt>
          <dd>{trade.exit_reason ?? "—"}</dd>
        </div>
        <div>
          <dt>RSI</dt>
          <dd>{trade.rsi_value?.toFixed(0) ?? "—"}</dd>
        </div>
        <div>
          <dt>pillars</dt>
          <dd>{trade.pillars_fired ?? "—"}</dd>
        </div>
      </dl>
    </div>
  );
}
