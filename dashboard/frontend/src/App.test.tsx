import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import App from "./App";
import * as api from "./api";
import type { Run } from "./types";

const runs = [
  { id: "run-1", label: "emaCeil_3,0atr", win_rate: 0.88, net_avg_pnl: 0.85 } as unknown as Run,
  { id: "run-0", label: "older", win_rate: 0.5, net_avg_pnl: -0.1 } as unknown as Run,
];

beforeEach(() => {
  vi.spyOn(api, "getRuns").mockResolvedValue(runs);
  vi.spyOn(api, "getTrades").mockResolvedValue([]);
  vi.spyOn(api, "getFeedback").mockResolvedValue([]);
  vi.spyOn(api, "getMarks").mockResolvedValue([]);
  vi.spyOn(api, "getSlices").mockResolvedValue({ baseline_win: 0, trades: 0, buckets: [] });
});
afterEach(() => vi.restoreAllMocks());

describe("App shell", () => {
  it("shows both tabs with Trades active by default", async () => {
    render(<App />);
    expect(screen.getByRole("tab", { name: /trades/i })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tab", { name: /overview/i })).toHaveAttribute("aria-selected", "false");
  });

  it("selects the newest run and lets you switch runs", async () => {
    render(<App />);
    await waitFor(() => expect(screen.getByLabelText(/run/i)).toHaveValue("run-1"));
    await userEvent.selectOptions(screen.getByLabelText(/run/i), "run-0");
    expect(screen.getByLabelText(/run/i)).toHaveValue("run-0");
  });

  it("switches to Overview when its tab is tapped", async () => {
    render(<App />);
    await userEvent.click(screen.getByRole("tab", { name: /overview/i }));
    expect(screen.getByRole("tab", { name: /overview/i })).toHaveAttribute("aria-selected", "true");
  });
});
