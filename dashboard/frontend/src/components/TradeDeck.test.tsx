import type { ComponentProps } from "react";
import { render, screen, waitFor } from "@testing-library/react";
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

  it("fetches each trade's detail exactly once even though the prefetch effect re-renders on every arrival", async () => {
    renderDeck();
    await waitFor(() => expect(screen.getByText(/1 of 2/i)).toBeInTheDocument());
    await waitFor(() => expect(api.getTrade).toHaveBeenCalledWith("t-b"));
    // Give any redundant re-fetch a chance to fire before asserting the count.
    await new Promise((r) => setTimeout(r, 0));
    const callsFor = (id: string) =>
      (api.getTrade as unknown as { mock: { calls: unknown[][] } }).mock.calls.filter(
        (args) => args[0] === id
      );
    expect(callsFor("t-a")).toHaveLength(1);
    expect(callsFor("t-b")).toHaveLength(1);
    expect(api.getTrade).toHaveBeenCalledTimes(2);
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

  it("falls through to the empty state when the trade list fails to load", async () => {
    vi.spyOn(api, "getTrades").mockRejectedValue(new Error("boom"));
    renderDeck();
    // Must not spin on "Loading trades…" forever, and must not raise an unhandled rejection.
    await waitFor(() => expect(screen.getByText(/no trades for this filter/i)).toBeInTheDocument());
  });
});
