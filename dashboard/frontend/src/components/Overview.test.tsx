import { render, screen, waitFor } from "@testing-library/react";
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import Overview from "./Overview";
import * as api from "../api";
import type { Run, TradeSummary } from "../types";

const run = {
  id: "run-1",
  label: "emaCeil_3,0atr",
  win_rate: 0.882,
  net_avg_pnl: 0.85,
  net_avg_pnl_usd: 4.25,
  trades_per_day: 0.3,
  avg_r: 0.1,
} as unknown as Run;

const trades = [
  { entry_time: "2025-01-01T10:00:00Z", net_pnl: 1 },
  { entry_time: "2025-01-02T10:00:00Z", net_pnl: -2 },
  { entry_time: "2025-01-03T10:00:00Z", net_pnl: 3 },
] as unknown as TradeSummary[];

beforeEach(() => {
  vi.spyOn(api, "getTrades").mockResolvedValue(trades);
  vi.spyOn(api, "getSlices").mockResolvedValue({
    baseline_win: 0.5,
    trades: 8,
    buckets: [
      { lo: 0.1, hi: 1.0, count: 4, win_pct: 1.0, net_avg_pnl: 1 },
      { lo: 4.0, hi: 6.0, count: 4, win_pct: 0.0, net_avg_pnl: -2 },
    ],
  });
});
afterEach(() => vi.restoreAllMocks());

describe("Overview", () => {
  it("shows the headline KPIs", async () => {
    render(<Overview run={run} />);
    expect(screen.getByText("88%")).toBeInTheDocument();
    expect(screen.getByText("+0.85")).toBeInTheDocument(); // net pts/trade
    expect(screen.getByText("+4.25")).toBeInTheDocument(); // net $/trade
    expect(screen.getByText("0.3")).toBeInTheDocument();
  });

  it("plots an equity curve with one point per trade", async () => {
    render(<Overview run={run} />);
    await waitFor(() => expect(screen.getByTestId("equity-curve")).toBeInTheDocument());
    const points = screen.getByTestId("equity-curve").getAttribute("points") ?? "";
    expect(points.trim().split(/\s+/)).toHaveLength(3);
  });

  it("renders one bar per slice bucket", async () => {
    render(<Overview run={run} />);
    await waitFor(() => expect(screen.getAllByTestId("slice-bar")).toHaveLength(2));
  });

  it("renders the sample size (count) for each bucket so a thin bucket isn't mistaken for a robust one", async () => {
    render(<Overview run={run} />);
    await waitFor(() => expect(screen.getAllByTestId("slice-bar")).toHaveLength(2));
    expect(screen.getAllByText("n=4")).toHaveLength(2);
  });

  it("does not throw and still renders KPI tiles when getTrades rejects", async () => {
    vi.spyOn(api, "getTrades").mockRejectedValue(new Error("network down"));
    render(<Overview run={run} />);
    expect(screen.getByText("88%")).toBeInTheDocument();
    await waitFor(() => expect(screen.getAllByTestId("slice-bar")).toHaveLength(2));
  });
});
