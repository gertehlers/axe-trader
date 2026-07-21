import type { Bar } from "./types";

/** Index of the first bar whose end time is at or after `iso`; clamps to the last bar. */
export function barIndexAtOrAfter(bars: Bar[], iso: string): number {
  const target = Date.parse(iso) / 1000;
  for (let i = 0; i < bars.length; i++) {
    if (bars[i].t >= target) return i;
  }
  return Math.max(0, bars.length - 1);
}

/** Focus view: entry → exit with `pad` bars either side, clamped to the series. */
export function focusWindow(
  bars: Bar[],
  entryIso: string,
  exitIso: string,
  pad = 10
): { from: number; to: number } {
  const entry = barIndexAtOrAfter(bars, entryIso);
  const exit = barIndexAtOrAfter(bars, exitIso);
  return {
    from: Math.max(0, Math.min(entry, exit) - pad),
    to: Math.min(bars.length - 1, Math.max(entry, exit) + pad),
  };
}

/** Vertical range over the visible bars, widened to include stop/target and padded 2%. */
export function priceRange(bars: Bar[], extras: number[] = []): { min: number; max: number } {
  const lows = bars.map((b) => b.l).concat(extras);
  const highs = bars.map((b) => b.h).concat(extras);
  const min = Math.min(...lows);
  const max = Math.max(...highs);
  const pad = (max - min) * 0.02 || 1;
  return { min: min - pad, max: max + pad };
}

export interface Scales {
  x(index: number): number;
  y(price: number): number;
  barWidth: number;
}

export function makeScales(opts: {
  count: number;
  min: number;
  max: number;
  width: number;
  height: number;
}): Scales {
  const { count, min, max, width, height } = opts;
  const slot = width / Math.max(1, count);
  return {
    x: (i) => slot * (i + 0.5),
    y: (price) => height - ((price - min) / (max - min || 1)) * height,
    barWidth: Math.max(1, slot * 0.6),
  };
}

/** Nearest bar index for a tap at `x` pixels; clamps to the series. */
export function barIndexAtX(x: number, count: number, width: number): number {
  const slot = width / Math.max(1, count);
  return Math.min(count - 1, Math.max(0, Math.floor(x / slot)));
}
