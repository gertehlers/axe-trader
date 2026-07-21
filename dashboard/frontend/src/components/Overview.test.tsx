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
  max_drawdown: 3.5, // stored as a non-negative magnitude; rendered as a give-back
  worst_quarter_net: -12.25,
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
      { lo: 4.0, hi: 6.0, count: 2, win_pct: 0.0, net_avg_pnl: -2 },
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
    // A drawdown is stored positive but must never display as "+3.50" -- that reads as a gain.
    expect(screen.getByText("−3.50")).toBeInTheDocument(); // max DD (U+2212 minus)
    expect(screen.getByText("-12.25")).toBeInTheDocument(); // worst qtr
  });

  it("plots an equity curve with one point per trade in the exact chart coordinates", async () => {
    render(<Overview run={run} />);
    await waitFor(() => expect(screen.getByTestId("equity-curve")).toBeInTheDocument());
    const points = screen.getByTestId("equity-curve").getAttribute("points") ?? "";
    // cumulative net_pnl in entry order: 1, -1, 2 -> lo=-1, hi=2, W=320, H=90
    expect(points).toBe("0.0,30.0 160.0,90.0 320.0,0.0");
  });

  it("sorts trades into entry order before plotting, regardless of input order", async () => {
    vi.spyOn(api, "getTrades").mockResolvedValue(
      [...trades].reverse() as unknown as TradeSummary[]
    );
    render(<Overview run={run} />);
    await waitFor(() => expect(screen.getByTestId("equity-curve")).toBeInTheDocument());
    const points = screen.getByTestId("equity-curve").getAttribute("points") ?? "";
    expect(points).toBe("0.0,30.0 160.0,90.0 320.0,0.0");
  });

  it("renders one bar per slice bucket", async () => {
    render(<Overview run={run} />);
    await waitFor(() => expect(screen.getAllByTestId("slice-bar")).toHaveLength(2));
  });

  it("renders the sample size (count) for each bucket so a thin bucket isn't mistaken for a robust one", async () => {
    render(<Overview run={run} />);
    await waitFor(() => expect(screen.getAllByTestId("slice-bar")).toHaveLength(2));
    expect(screen.getAllByText("n=4")).toHaveLength(1);
    expect(screen.getAllByText("n=2")).toHaveLength(1);
  });

  it("does not throw and still renders KPI tiles when getTrades rejects", async () => {
    vi.spyOn(api, "getTrades").mockRejectedValue(new Error("network down"));
    render(<Overview run={run} />);
    expect(screen.getByText("88%")).toBeInTheDocument();
    await waitFor(() => expect(screen.getAllByTestId("slice-bar")).toHaveLength(2));
  });

  it("shows a load-failure message instead of a blank equity curve when getTrades rejects", async () => {
    vi.spyOn(api, "getTrades").mockRejectedValue(new Error("network down"));
    render(<Overview run={run} />);
    await waitFor(() => expect(screen.getByText("Couldn't load trade data.")).toBeInTheDocument());
    expect(screen.queryByTestId("equity-curve")).not.toBeInTheDocument();
  });

  it("shows a no-trades message instead of a blank equity curve when there are zero trades", async () => {
    vi.spyOn(api, "getTrades").mockResolvedValue([]);
    render(<Overview run={run} />);
    await waitFor(() => expect(screen.getByText("No trades in this run.")).toBeInTheDocument());
    expect(screen.queryByTestId("equity-curve")).not.toBeInTheDocument();
  });

  it("renders a visible marker for a single-trade equity curve", async () => {
    vi.spyOn(api, "getTrades").mockResolvedValue([trades[0]]);
    render(<Overview run={run} />);
    await waitFor(() => expect(screen.getByTestId("equity-curve")).toBeInTheDocument());
    expect(document.querySelector("circle")).toBeInTheDocument();
  });
});
