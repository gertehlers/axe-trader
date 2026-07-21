import type { ComponentProps } from "react";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import TradeDeck from "./TradeDeck";
import * as api from "../api";
import type { Trade, TradeSummary } from "../types";

const t0 = Date.parse("2025-03-04T14:00:00Z") / 1000;
const bars = Array.from({ length: 40 }, (_, i) => ({
  t: t0 + i * 300,
  o: 100,
  h: 101,
  l: 99,
  c: 100,
}));

const summary = (id: string, key: string): TradeSummary =>
  ({
    id,
    run_id: "run-1",
    signal_key: key,
    entry_time: "2025-03-04T15:00:00Z",
    exit_time: "2025-03-04T15:30:00Z",
    direction: "LONG",
    entry_price: 100,
    exit_price: 101,
    stop_price: 97,
    target_price: 101.5,
    pnl: 1,
    net_pnl: 0.8,
    r_multiple: 0.3,
    exit_reason: "TARGET",
    is_win: 1,
    rsi_value: 28,
    pillars_fired: "RSI+BB",
  }) as unknown as TradeSummary;

const KEY_A = "US500|2025-03-04T15:00:00Z|LONG";
const KEY_B = "US500|2025-03-05T15:00:00Z|LONG";
const KEY_C = "US500|2025-03-06T15:00:00Z|LONG";

beforeEach(() => {
  vi.spyOn(api, "getTrades").mockResolvedValue([summary("t-a", KEY_A), summary("t-b", KEY_B)]);
  vi.spyOn(api, "getTrade").mockImplementation(
    async (id) => ({ ...summary(id, id === "t-a" ? KEY_A : KEY_B), bars_json: JSON.stringify(bars) }) as Trade
  );
});
afterEach(() => vi.restoreAllMocks());

const noop = () => {};

function renderDeck(overrides: Partial<ComponentProps<typeof TradeDeck>> = {}) {
  return render(
    <TradeDeck
      runId="run-1"
      flags={new Map()}
      marks={new Map()}
      onFlag={noop}
      onToggleMark={noop}
      {...overrides}
    />
  );
}

describe("TradeDeck", () => {
  it("shows one trade and its position, and advances on next", async () => {
    renderDeck();
    await waitFor(() => expect(screen.getByText(/1 of 2/i)).toBeInTheDocument());
    await userEvent.click(screen.getByRole("button", { name: /next trade/i }));
    expect(screen.getByText(/2 of 2/i)).toBeInTheDocument();
  });

  it("refetches when the filter changes", async () => {
    renderDeck();
    await waitFor(() => expect(api.getTrades).toHaveBeenCalledWith("run-1", "all"));
    await userEvent.selectOptions(screen.getByLabelText(/filter/i), "losers");
    await waitFor(() => expect(api.getTrades).toHaveBeenCalledWith("run-1", "losers"));
  });

  it("reports a flag tap with the current trade's signal_key", async () => {
    const onFlag = vi.fn();
    renderDeck({ onFlag });
    await waitFor(() => expect(screen.getByText(/1 of 2/i)).toBeInTheDocument());
    await userEvent.click(screen.getByRole("button", { name: "chop" }));
    expect(onFlag).toHaveBeenCalledWith(KEY_A, "chop");
  });

  it("taps a candle with the selected kind and reports the bar timestamp", async () => {
    const onToggleMark = vi.fn();
    renderDeck({ onToggleMark });
    await waitFor(() => expect(screen.getByText(/1 of 2/i)).toBeInTheDocument());

    await userEvent.click(screen.getByRole("button", { name: "T1" }));
    // findBy, not getBy: "1 of 2" renders as soon as the list lands, but the chart only appears
    // once that trade's detail fetch resolves a tick later.
    const svg = await screen.findByTestId("candles");
    svg.getBoundingClientRect = () => ({ left: 0, top: 0, width: 320, height: 180 }) as DOMRect;
    await userEvent.click(svg);

    expect(onToggleMark).toHaveBeenCalledTimes(1);
    const [key, kind, iso] = onToggleMark.mock.calls[0];
    expect(key).toBe(KEY_A);
    expect(kind).toBe("T1");
    expect(Number.isNaN(Date.parse(iso))).toBe(false);
  });

  it("does not place a mark when no kind is selected", async () => {
    const onToggleMark = vi.fn();
    renderDeck({ onToggleMark });
    await waitFor(() => expect(screen.getByText(/1 of 2/i)).toBeInTheDocument());
    // findBy, not getBy — see the note in the preceding test.
    const svg = await screen.findByTestId("candles");
    svg.getBoundingClientRect = () => ({ left: 0, top: 0, width: 320, height: 180 }) as DOMRect;
    await userEvent.click(svg);
    expect(onToggleMark).not.toHaveBeenCalled();
  });

  it("does not issue a duplicate fetch for a trade whose neighbour arrives first (guards against `detail` re-running the prefetch effect)", async () => {
    // Staggered, gate-controlled resolution — unlike two plain-async mocks settling in the same
    // microtask drain (which batches both setDetail calls into one render and never re-runs the
    // effect either way), this reproduces the bug the plan's original code had: deps
    // `[list, index, detail]` with a `detail[row.id]` skip-guard. There, resolving t-a's fetch
    // changes `detail`, which re-runs the effect; t-b's fetch is still pending so `detail[t-b]`
    // is still absent, and a second request for t-b fires before the first one has even settled.
    const resolvers: Record<string, Array<(t: Trade) => void>> = { "t-a": [], "t-b": [], "t-c": [] };
    vi.spyOn(api, "getTrades").mockResolvedValue([
      summary("t-a", KEY_A),
      summary("t-b", KEY_B),
      summary("t-c", KEY_C),
    ]);
    vi.spyOn(api, "getTrade").mockImplementation(
      (id) =>
        new Promise<Trade>((resolve) => {
          resolvers[id].push(resolve);
        })
    );

    renderDeck();
    await waitFor(() => expect(screen.getByText(/1 of 3/i)).toBeInTheDocument());
    // index 0 prefetches itself (t-a) and its next neighbour (t-b); t-c is out of the window.
    await waitFor(() => {
      expect(resolvers["t-a"]).toHaveLength(1);
      expect(resolvers["t-b"]).toHaveLength(1);
    });

    // Resolve t-a only. t-b's fetch is still in flight.
    resolvers["t-a"][0]({ ...summary("t-a", KEY_A), bars_json: JSON.stringify(bars) } as Trade);
    await waitFor(() => expect(screen.getByTestId("candles")).toBeInTheDocument());
    // Give any redundant re-fetch a chance to fire before asserting the count.
    await new Promise((r) => setTimeout(r, 0));
    expect(resolvers["t-b"]).toHaveLength(1);
  });

  it("still renders the chart when the user advances before the detail fetches resolve", async () => {
    // Regression: the request-once ref and a cleanup `live` guard are mutually destructive.
    // Changing `index` runs the effect cleanup; if that discarded the in-flight responses, the
    // ref would suppress any refetch and the deck would sit on "Loading chart…" forever. This is
    // the ordinary phone case — swiping on before the chart has loaded.
    const gates: Array<() => void> = [];
    vi.spyOn(api, "getTrade").mockImplementation(
      (id) =>
        new Promise<Trade>((resolve) => {
          gates.push(() =>
            resolve({
              ...summary(id, id === "t-a" ? KEY_A : KEY_B),
              bars_json: JSON.stringify(bars),
            } as Trade)
          );
        })
    );

    renderDeck();
    await waitFor(() => expect(screen.getByText(/1 of 2/i)).toBeInTheDocument());
    await waitFor(() => expect(gates.length).toBe(2));

    // Advance while both requests are still open.
    await userEvent.click(screen.getByRole("button", { name: /next trade/i }));
    expect(screen.getByText(/2 of 2/i)).toBeInTheDocument();

    for (const open of gates) open();

    await waitFor(() => expect(screen.getByTestId("candles")).toBeInTheDocument());
  });

  it("shows a distinct failed state (not the empty state) when the trade list fails to load, and retries", async () => {
    vi.spyOn(api, "getTrades")
      .mockRejectedValueOnce(new Error("boom"))
      .mockResolvedValueOnce([summary("t-a", KEY_A), summary("t-b", KEY_B)]);
    renderDeck();
    // Must not spin on "Loading trades…" forever, must not raise an unhandled rejection, and must
    // not be confusable with a genuinely empty filter — an expired session looks identical to "no
    // trades" otherwise, and the user stops reviewing a run that actually has trades in it.
    await waitFor(() => expect(screen.getByText(/couldn't load trades/i)).toBeInTheDocument());
    expect(screen.queryByText(/no trades for this filter/i)).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: /retry/i }));
    await waitFor(() => expect(screen.getByText(/1 of 2/i)).toBeInTheDocument());
  });

  it("still shows the genuinely-empty state (unchanged) when the filter has no trades", async () => {
    vi.spyOn(api, "getTrades").mockResolvedValue([]);
    renderDeck();
    await waitFor(() => expect(screen.getByText(/no trades for this filter/i)).toBeInTheDocument());
  });

  it("shows a distinct failed state (not indefinite loading) when the current trade's detail fails to load, and retries", async () => {
    vi.spyOn(api, "getTrade")
      .mockRejectedValueOnce(new Error("boom")) // t-a, the trade at index 0
      .mockImplementation(
        async (id) =>
          ({ ...summary(id, id === "t-a" ? KEY_A : KEY_B), bars_json: JSON.stringify(bars) }) as Trade
      );
    renderDeck();
    await waitFor(() => expect(screen.getByText(/couldn't load chart/i)).toBeInTheDocument());

    await userEvent.click(screen.getByRole("button", { name: /retry/i }));
    await waitFor(() => expect(screen.getByTestId("candles")).toBeInTheDocument());
  });
});

describe("TradeDeck swipe gestures", () => {
  async function waitForFirst() {
    await waitFor(() => expect(screen.getByText(/1 of 2/i)).toBeInTheDocument());
  }

  it("advances on a predominantly horizontal swipe past the threshold", async () => {
    renderDeck();
    await waitForFirst();
    const deck = screen.getByTestId("deck");
    fireEvent.touchStart(deck, { touches: [{ clientX: 200, clientY: 100 }] });
    fireEvent.touchEnd(deck, { changedTouches: [{ clientX: 140, clientY: 100 }] }); // dx=-60, dy=0
    expect(screen.getByText(/2 of 2/i)).toBeInTheDocument();
  });

  it("does not advance on a predominantly vertical drag with a >50px horizontal component (guards F1)", async () => {
    renderDeck();
    await waitForFirst();
    const deck = screen.getByTestId("deck");
    // ~50px horizontal over a 200px vertical drag — the natural thumb arc reaching the chips below.
    fireEvent.touchStart(deck, { touches: [{ clientX: 100, clientY: 50 }] });
    fireEvent.touchEnd(deck, { changedTouches: [{ clientX: 150, clientY: 250 }] }); // dx=50, dy=200
    expect(screen.getByText(/1 of 2/i)).toBeInTheDocument();
  });

  it("does not advance on a two-finger gesture (guards F2)", async () => {
    renderDeck();
    await waitForFirst();
    const deck = screen.getByTestId("deck");
    // Finger 1 touches down.
    fireEvent.touchStart(deck, { touches: [{ clientX: 100, clientY: 50 }] });
    // Finger 2 joins — a real touchstart reports ALL active touches, mimicking a pinch-zoom attempt.
    fireEvent.touchStart(deck, {
      touches: [
        { clientX: 100, clientY: 50 },
        { clientX: 220, clientY: 50 },
      ],
    });
    // Finger 2 lifts first, far from where finger 1 started — a phantom horizontal delta if paired.
    fireEvent.touchEnd(deck, { changedTouches: [{ clientX: 400, clientY: 50 }] });
    expect(screen.getByText(/1 of 2/i)).toBeInTheDocument();
  });

  it("does not advance when the horizontal movement is below SWIPE_PX", async () => {
    renderDeck();
    await waitForFirst();
    const deck = screen.getByTestId("deck");
    fireEvent.touchStart(deck, { touches: [{ clientX: 100, clientY: 50 }] });
    fireEvent.touchEnd(deck, { changedTouches: [{ clientX: 130, clientY: 50 }] }); // dx=30
    expect(screen.getByText(/1 of 2/i)).toBeInTheDocument();
  });
});
