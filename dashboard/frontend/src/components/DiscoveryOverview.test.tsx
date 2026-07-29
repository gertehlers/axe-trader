import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import DiscoveryOverview from "./DiscoveryOverview";
import type { DiscoveryReport } from "../types";

const report = {
  run: { id: "d-1", instrument: "US500", timeframe_minutes: 5, promoted_candidate: "candidate-a", total_net: 12.5, maximum_drawdown: 4, net_to_maximum_drawdown: 3.125, profitable_sampled_month_percentage: 66.7 },
  directions: { LONG: { total_net: 8, trade_count: 4 }, SHORT: { total_net: 4.5, trade_count: 3 } },
  patterns: [{ id: "candidate-a", direction: "LONG", clauses: [{ feature: "rsi", operator: "LT", threshold: 30 }], independent_zones: 4 }],
  monthly_results: [{ candidate_id: "candidate-a", month: "2025-02", trade_count: 12, sampled: true, net_pnl: 3.5 }],
  examples: ["best", "median", "worst", "false-positive", "missed-run"].map((kind) => ({ kind, oracle_result: { status: "RUN", mfe_atr: 2 }, executable_result: { capture_ratio: 0.7 }, chart_window: [{ close: 100 }] })),
} as unknown as DiscoveryReport;

describe("DiscoveryOverview", () => {
  it("renders the audit KPIs, directional results, monthly sample size, rules, and example evidence", () => {
    render(<DiscoveryOverview report={report} />);
    expect(screen.getByText("+12.50")).toBeInTheDocument();
    expect(screen.getByText("−4.00")).toBeInTheDocument();
    expect(screen.getByText("67%")).toBeInTheDocument();
    expect(screen.getByText("3.13")).toBeInTheDocument();
    expect(screen.getByText(/long.*\+8\.00/i)).toBeInTheDocument();
    expect(screen.getByText(/short.*\+4\.50/i)).toBeInTheDocument();
    expect(screen.getByText(/2025-02.*\+3\.50.*n=12/i)).toBeInTheDocument();
    expect(screen.getByText(/rsi LT 30/i)).toBeInTheDocument();
    expect(screen.getByText(/4 independent zones/i)).toBeInTheDocument();
    expect(screen.getAllByText(/oracle: RUN/i)).toHaveLength(5);
    expect(screen.getAllByText(/capture: 0\.70/i)).toHaveLength(5);
    for (const kind of ["best", "median", "worst", "false-positive", "missed-run"]) {
      expect(screen.getByRole("heading", { name: kind })).toBeInTheDocument();
    }
  });
});
