import { useEffect, useRef, useState } from "react";
import { FLAGS, MARK_KINDS, type Flag, type MarkKind } from "../../../shared/vocab";
import * as api from "../api";
import type { Bar, Mark, Trade, TradeFilter, TradeSummary } from "../types";
import TradeCard, { type Zoom } from "./TradeCard";

interface Props {
  runId: string;
  flags: Map<string, Flag>;
  marks: Map<string, Mark[]>;
  onFlag: (signalKey: string, flag: Flag) => void;
  onToggleMark: (signalKey: string, kind: MarkKind, barIso: string) => void;
}

const SWIPE_PX = 50;

export default function TradeDeck({ runId, flags, marks, onFlag, onToggleMark }: Props) {
  const [filter, setFilter] = useState<TradeFilter>("all");
  const [list, setList] = useState<TradeSummary[]>([]);
  const [loadingList, setLoadingList] = useState(true);
  const [index, setIndex] = useState(0);
  const [detail, setDetail] = useState<Record<string, { trade: Trade; bars: Bar[] }>>({});
  const [kind, setKind] = useState<MarkKind | null>(null);
  const [zoom, setZoom] = useState<Zoom>("focus");
  const touchStartX = useRef<number | null>(null);
  // Tracks trade ids we've already requested (or hold), so the prefetch effect below never
  // issues a second request for the same trade — see the effect's comment for why this matters.
  const requested = useRef<Set<string>>(new Set());

  // Unlike the detail cache below, the `live` guard here is genuinely needed: this writes "the
  // current list", so a slow response for an older filter must not overwrite a newer one.
  useEffect(() => {
    let live = true;
    setLoadingList(true);
    api
      .getTrades(runId, filter)
      .then((rows) => {
        if (!live) return;
        setList(rows);
        setIndex(0);
      })
      .catch(() => {
        // Fall through to the empty state rather than spinning forever on a failed load.
        if (live) setList([]);
      })
      .finally(() => {
        if (live) setLoadingList(false);
      });
    return () => {
      live = false;
    };
  }, [runId, filter]);

  // Fetch the current trade's bars, and prefetch its neighbours so swiping is instant.
  //
  // `detail` is deliberately NOT a dependency here: this effect writes to `detail`, and if it
  // also depended on `detail` then every resolved fetch would change the dependency and re-run
  // the effect (a quadratic burst of redundant fetches). Instead we track "have we ever requested
  // this id" in a ref that survives across renders, so each trade is fetched exactly once.
  //
  // There is deliberately NO `live` flag / cleanup here, and that pairing is load-bearing: with a
  // request-once ref, discarding an in-flight response on cleanup would strand that trade forever,
  // because the ref then suppresses any refetch. Changing `index` (swiping to the next trade
  // before its fetch resolves — the common case on a phone) runs the cleanup, so a `live` guard
  // would leave the deck stuck on "Loading chart…" permanently. It is safe to drop because
  // `detail` is an id-keyed *cache*, not "the currently displayed thing": a late response is
  // filed under its own trade id and can never be mistaken for another trade's. Setting state
  // after unmount is a no-op in React 18+, so there is nothing left for a cleanup to protect.
  useEffect(() => {
    for (const i of [index, index + 1, index - 1]) {
      const row = list[i];
      if (!row || requested.current.has(row.id)) continue;
      requested.current.add(row.id);
      api
        .getTrade(row.id)
        .then((trade) => {
          setDetail((prev) => ({ ...prev, [trade.id]: { trade, bars: api.getBars(trade) } }));
        })
        .catch(() => {
          // Drop the claim so a later render can retry, rather than leaving this trade
          // permanently unfetchable behind the request-once guard.
          requested.current.delete(row.id);
        });
    }
  }, [list, index]);

  if (loadingList) return <p className="loading">Loading trades…</p>;
  if (list.length === 0) return <p className="empty">No trades for this filter.</p>;

  const current = list[index];
  const loaded = detail[current.id];
  const go = (delta: number) => setIndex((i) => Math.min(list.length - 1, Math.max(0, i + delta)));

  return (
    <section
      className="deck"
      onTouchStart={(e) => (touchStartX.current = e.touches[0].clientX)}
      onTouchEnd={(e) => {
        const start = touchStartX.current;
        touchStartX.current = null;
        if (start === null) return;
        const dx = e.changedTouches[0].clientX - start;
        if (Math.abs(dx) >= SWIPE_PX) go(dx < 0 ? 1 : -1);
      }}
    >
      <header className="deck-head">
        <label>
          Filter
          <select value={filter} onChange={(e) => setFilter(e.target.value as TradeFilter)}>
            <option value="all">all</option>
            <option value="losers">losers</option>
            <option value="winners">winners</option>
          </select>
        </label>
        <span>
          {index + 1} of {list.length}
        </span>
      </header>

      {loaded ? (
        <TradeCard
          trade={loaded.trade}
          bars={loaded.bars}
          marks={marks.get(current.signal_key) ?? []}
          zoom={zoom}
          onZoomChange={setZoom}
          onBarTap={(barIso) => {
            if (kind) onToggleMark(current.signal_key, kind, barIso);
          }}
        />
      ) : (
        <p className="loading">Loading chart…</p>
      )}

      <div className="chips kinds" role="group" aria-label="mark kind">
        {MARK_KINDS.map((k) => (
          <button
            key={k}
            aria-pressed={kind === k}
            onClick={() => setKind(kind === k ? null : k)}
          >
            {k}
          </button>
        ))}
      </div>

      <div className="chips flags" role="group" aria-label="cause">
        {FLAGS.map((f) => (
          <button
            key={f}
            aria-pressed={flags.get(current.signal_key) === f}
            onClick={() => onFlag(current.signal_key, f)}
          >
            {f}
          </button>
        ))}
      </div>

      <nav className="deck-nav">
        <button aria-label="previous trade" onClick={() => go(-1)} disabled={index === 0}>
          ‹
        </button>
        <button
          aria-label="next trade"
          onClick={() => go(1)}
          disabled={index === list.length - 1}
        >
          ›
        </button>
      </nav>
    </section>
  );
}
