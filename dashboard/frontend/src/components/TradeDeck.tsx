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
  const [listFailed, setListFailed] = useState(false);
  const [listReloadKey, setListReloadKey] = useState(0);
  // Ids whose detail fetch has failed, so the UI can show a distinct "couldn't load" state for the
  // currently displayed trade instead of sitting on "Loading chart…" forever.
  const [detailFailed, setDetailFailed] = useState<Set<string>>(new Set());
  const touchStartX = useRef<number | null>(null);
  const touchStartY = useRef<number | null>(null);
  // Tracks trade ids we've already requested (or hold), so the prefetch effect below never
  // issues a second request for the same trade — see the effect's comment for why this matters.
  const requested = useRef<Set<string>>(new Set());

  // Unlike the detail cache below, the `live` guard here is genuinely needed: this writes "the
  // current list", so a slow response for an older filter must not overwrite a newer one.
  useEffect(() => {
    let live = true;
    setLoadingList(true);
    setListFailed(false);
    api
      .getTrades(runId, filter)
      .then((rows) => {
        if (!live) return;
        setList(rows);
        setIndex(0);
      })
      .catch(() => {
        // Distinguish this from a genuinely empty filter — an expired session must not look like
        // "no trades" — and give the user a retry rather than spinning forever on a failed load.
        if (live) {
          setList([]);
          setListFailed(true);
        }
      })
      .finally(() => {
        if (live) setLoadingList(false);
      });
    return () => {
      live = false;
    };
  }, [runId, filter, listReloadKey]);

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
  // Shared by the prefetch effect below and the "Retry" button in the failed-chart state, so a
  // manual retry re-runs exactly the same request/claim/failure bookkeeping as the initial fetch.
  const fetchTrade = (id: string) => {
    requested.current.add(id);
    api
      .getTrade(id)
      .then((trade) => {
        setDetail((prev) => ({ ...prev, [trade.id]: { trade, bars: api.getBars(trade) } }));
        setDetailFailed((prev) => {
          if (!prev.has(id)) return prev;
          const next = new Set(prev);
          next.delete(id);
          return next;
        });
      })
      .catch(() => {
        // Drop the claim so a later render (or an explicit retry) can retry, rather than leaving
        // this trade permanently unfetchable behind the request-once guard.
        requested.current.delete(id);
        setDetailFailed((prev) => new Set(prev).add(id));
      });
  };

  useEffect(() => {
    for (const i of [index, index + 1, index - 1]) {
      const row = list[i];
      if (!row || requested.current.has(row.id)) continue;
      fetchTrade(row.id);
    }
  }, [list, index]);

  if (loadingList) return <p className="loading">Loading trades…</p>;
  if (listFailed)
    return (
      <p className="error">
        Couldn't load trades.{" "}
        <button onClick={() => setListReloadKey((k) => k + 1)}>Retry</button>
      </p>
    );
  if (list.length === 0) return <p className="empty">No trades for this filter.</p>;

  const current = list[index];
  const loaded = detail[current.id];
  const go = (delta: number) => setIndex((i) => Math.min(list.length - 1, Math.max(0, i + delta)));

  return (
    <section
      className="deck"
      data-testid="deck"
      onTouchStart={(e) => {
        // Only track single-finger gestures. A real touchstart reports ALL active touches, so
        // with a second finger down (e.g. the user pinch-zooming the chart, which the design spec
        // explicitly rejects) this would otherwise reuse finger 1's position as the swipe origin —
        // paired with whichever finger lifts first in onTouchEnd, that produces a phantom
        // horizontal delta well over the threshold.
        if (e.touches.length !== 1) {
          touchStartX.current = null;
          touchStartY.current = null;
          return;
        }
        touchStartX.current = e.touches[0].clientX;
        touchStartY.current = e.touches[0].clientY;
      }}
      onTouchEnd={(e) => {
        const startX = touchStartX.current;
        const startY = touchStartY.current;
        touchStartX.current = null;
        touchStartY.current = null;
        if (startX === null || startY === null) return;
        const dx = e.changedTouches[0].clientX - startX;
        const dy = e.changedTouches[0].clientY - startY;
        // Ignore drags that are more vertical than horizontal — e.g. scrolling down the card with
        // a natural thumb arc to reach the chips below the chart — so they can't be misread as a
        // swipe and advance the deck out from under the user.
        if (Math.abs(dx) <= Math.abs(dy)) return;
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
      ) : detailFailed.has(current.id) ? (
        <p className="error">
          Couldn't load chart. <button onClick={() => fetchTrade(current.id)}>Retry</button>
        </p>
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
