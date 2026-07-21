import { render, screen, fireEvent } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, it, expect, vi } from "vitest";
import TradeCard, { VIEW_W } from "./TradeCard";
import type { Bar, Trade } from "../types";

const t0 = Date.parse("2025-03-04T14:00:00Z") / 1000;
const bars: Bar[] = Array.from({ length: 60 }, (_, i) => ({
  t: t0 + i * 300,
  o: 100 + i * 0.1,
  h: 101 + i * 0.1,
  l: 99 + i * 0.1,
  c: 100 + i * 0.1,
}));

const trade = {
  id: "t-1",
  signal_key: "US500|2025-03-04T16:00:00Z|LONG",
  entry_time: "2025-03-04T16:00:00Z", // bar 24
  exit_time: "2025-03-04T16:30:00Z", // bar 30
  direction: "LONG",
  entry_price: 102.4,
  exit_price: 103.0,
  stop_price: 99.4,
  target_price: 103.15,
  pnl: 0.6,
  net_pnl: 0.4,
  r_multiple: 0.2,
  exit_reason: "TARGET",
  is_win: 1,
  rsi_value: 28,
  pillars_fired: "RSI+BB",
} as unknown as Trade;

describe("TradeCard", () => {
  it("draws stop and target lines at their prices, and entry/exit markers", () => {
    render(<TradeCard trade={trade} bars={bars} marks={[]} zoom="full" onZoomChange={() => {}} onBarTap={() => {}} />);
    const stop = screen.getByTestId("stop-line");
    const target = screen.getByTestId("target-line");
    // stop is below target on screen → larger y
    expect(Number(stop.getAttribute("y1"))).toBeGreaterThan(Number(target.getAttribute("y1")));
    expect(screen.getByTestId("entry-marker")).toBeInTheDocument();
    expect(screen.getByTestId("exit-marker")).toBeInTheDocument();
  });

  it("shows fewer candles in focus than in full", () => {
    const { rerender } = render(
      <TradeCard trade={trade} bars={bars} marks={[]} zoom="full" onZoomChange={() => {}} onBarTap={() => {}} />
    );
    const full = screen.getAllByTestId("candle").length;
    rerender(
      <TradeCard trade={trade} bars={bars} marks={[]} zoom="focus" onZoomChange={() => {}} onBarTap={() => {}} />
    );
    expect(screen.getAllByTestId("candle").length).toBeLessThan(full);
  });

  it("reports the tapped bar's timestamp", () => {
    const onBarTap = vi.fn();
    render(<TradeCard trade={trade} bars={bars} marks={[]} zoom="full" onZoomChange={() => {}} onBarTap={onBarTap} />);
    const svg = screen.getByTestId("candles");
    svg.getBoundingClientRect = () =>
      ({ left: 0, top: 0, width: VIEW_W, height: 180 }) as DOMRect;
    // tap in the middle → middle bar of the 60-bar full window (index 29 or 30)
    fireEvent.click(svg, { clientX: VIEW_W / 2, clientY: 90 });
    expect(onBarTap).toHaveBeenCalledTimes(1);
    const iso = onBarTap.mock.calls[0][0] as string;
    expect([bars[29].t, bars[30].t]).toContain(Date.parse(iso) / 1000);
  });

  it("renders one line per mark", () => {
    render(
      <TradeCard
        trade={trade}
        bars={bars}
        marks={[
          { id: "m1", signal_key: trade.signal_key, kind: "T1", bar_ts: "2025-03-04T16:20:00Z", created_at: "" },
          { id: "m2", signal_key: trade.signal_key, kind: "better-entry", bar_ts: "2025-03-04T15:50:00Z", created_at: "" },
        ]}
        zoom="full"
        onZoomChange={() => {}}
        onBarTap={() => {}}
      />
    );
    expect(screen.getByTestId("mark-T1")).toBeInTheDocument();
    expect(screen.getByTestId("mark-better-entry")).toBeInTheDocument();
  });

  it("switches zoom when the toggle is tapped", async () => {
    const onZoomChange = vi.fn();
    render(<TradeCard trade={trade} bars={bars} marks={[]} zoom="focus" onZoomChange={onZoomChange} onBarTap={() => {}} />);
    await userEvent.click(screen.getByRole("button", { name: /full/i }));
    expect(onZoomChange).toHaveBeenCalledWith("full");
  });
});
