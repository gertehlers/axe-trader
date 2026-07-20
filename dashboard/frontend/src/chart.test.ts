import { describe, it, expect } from "vitest";
import { barIndexAtOrAfter, focusWindow, priceRange, makeScales, barIndexAtX } from "./chart";
import type { Bar } from "./types";

// 20 bars, 5 minutes apart, starting 2025-03-04T14:00:00Z, price walking 100 → 119
const bars: Bar[] = Array.from({ length: 20 }, (_, i) => ({
  t: Date.parse("2025-03-04T14:00:00Z") / 1000 + i * 300,
  o: 100 + i,
  h: 101 + i,
  l: 99 + i,
  c: 100 + i,
}));

describe("chart geometry", () => {
  it("finds the first bar at or after a timestamp", () => {
    expect(barIndexAtOrAfter(bars, "2025-03-04T14:00:00Z")).toBe(0);
    expect(barIndexAtOrAfter(bars, "2025-03-04T14:12:00Z")).toBe(3); // 14:15 bar
    expect(barIndexAtOrAfter(bars, "2030-01-01T00:00:00Z")).toBe(19); // clamps to last
  });

  it("windows to entry..exit with padding, clamped to the series", () => {
    expect(focusWindow(bars, "2025-03-04T14:25:00Z", "2025-03-04T14:40:00Z", 2)).toEqual({
      from: 3,
      to: 10,
    });
    // padding beyond the ends clamps instead of going negative
    expect(focusWindow(bars, "2025-03-04T14:00:00Z", "2025-03-04T15:35:00Z", 10)).toEqual({
      from: 0,
      to: 19,
    });
  });

  it("includes stop/target prices in the vertical range", () => {
    const r = priceRange(bars.slice(0, 3), [90, 130]);
    expect(r.min).toBeLessThanOrEqual(90);
    expect(r.max).toBeGreaterThanOrEqual(130);
  });

  it("maps prices and indices into the viewport", () => {
    const s = makeScales({ count: 10, min: 100, max: 200, width: 300, height: 100 });
    expect(s.y(200)).toBeCloseTo(0); // highest price at the top
    expect(s.y(100)).toBeCloseTo(100); // lowest at the bottom
    expect(s.x(0)).toBeGreaterThan(0);
    expect(s.x(9)).toBeLessThan(300);
    expect(s.x(9)).toBeGreaterThan(s.x(0));
  });

  it("hit-tests a tap to the nearest bar", () => {
    const width = 300;
    const count = 10;
    const s = makeScales({ count, min: 0, max: 1, width, height: 100 });
    expect(barIndexAtX(s.x(4), count, width)).toBe(4);
    expect(barIndexAtX(-50, count, width)).toBe(0); // clamps left
    expect(barIndexAtX(9999, count, width)).toBe(9); // clamps right
  });
});
