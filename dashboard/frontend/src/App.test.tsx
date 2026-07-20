import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, it, expect } from "vitest";
import App from "./App";

describe("App shell", () => {
  it("shows both tabs with Trades active by default", async () => {
    render(<App />);
    expect(screen.getByRole("tab", { name: /trades/i })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tab", { name: /overview/i })).toHaveAttribute("aria-selected", "false");
  });

  it("switches to Overview when its tab is tapped", async () => {
    render(<App />);
    await userEvent.click(screen.getByRole("tab", { name: /overview/i }));
    expect(screen.getByRole("tab", { name: /overview/i })).toHaveAttribute("aria-selected", "true");
  });
});
