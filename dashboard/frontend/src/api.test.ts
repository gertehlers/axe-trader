import { describe, it, expect, vi, afterEach } from "vitest";
import { getTrades, getTrade, getSlices, postMark, deleteMark, ApiError, getBars } from "./api";
import type { Trade, TradeSummary } from "./types";

function mockFetch(body: unknown, status = 200) {
  const fn = vi.fn().mockResolvedValue(
    new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } })
  );
  vi.stubGlobal("fetch", fn);
  return fn;
}

/** A fully-populated trade row — every field the API validates as required is present. */
function completeTrade(overrides: Partial<Trade> = {}): Trade {
  return {
    id: "run-1:US500|2024-12-10T12:17:00Z|LONG",
    run_id: "run-1",
    signal_key: "US500|2024-12-10T12:17:00Z|LONG",
    entry_time: "2024-12-10T12:17:00Z",
    exit_time: "2024-12-10T13:00:00Z",
    direction: "LONG",
    entry_price: 5900,
    exit_price: 5910,
    stop_price: 5880,
    target_price: 5915,
    pnl: 10,
    net_pnl: 9.5,
    r_multiple: 0.5,
    exit_reason: "target",
    is_win: 1,
    rsi_value: 28,
    dist_to_trend_ema_atr: 0.2,
    atr_value: 5,
    atr_percentile: 40,
    volume_ratio: 1.1,
    hour_utc: 12,
    day_of_week: 2,
    volatility_regime: "normal",
    confluence_score: 4,
    pillars_fired: "rsi_bb,candles",
    bars_json: '[{"t":1,"o":2,"h":3,"l":1,"c":2}]',
    ...overrides,
  };
}

afterEach(() => vi.unstubAllGlobals());

describe("api client", () => {
  it("requests the filtered trade list for a run", async () => {
    const fetchMock = mockFetch([]);
    await getTrades("run-1", "losers");
    expect(fetchMock.mock.calls[0][0]).toBe("/api/runs/run-1/trades?filter=losers");
  });

  it("encodes a composite trade id in getTrade's URL", async () => {
    const id = "US500-1784554730790:US500|2024-12-10T12:17:00Z|LONG";
    const fetchMock = mockFetch(completeTrade({ id }));
    await getTrade(id);
    const url = fetchMock.mock.calls[0][0] as string;
    expect(url).toBe(
      "/api/trades/US500-1784554730790%3AUS500%7C2024-12-10T12%3A17%3A00Z%7CLONG"
    );
    expect(url).not.toContain("|");
  });

  it("encodes the run id in getTrades", async () => {
    const fetchMock = mockFetch([]);
    await getTrades("US500-1784554730790:with|pipe", "all");
    expect(fetchMock.mock.calls[0][0]).toBe(
      "/api/runs/US500-1784554730790%3Awith%7Cpipe/trades?filter=all"
    );
  });

  it("encodes the run id in getSlices", async () => {
    const fetchMock = mockFetch({ baseline_win: 0, trades: 0, buckets: [] });
    await getSlices("US500-1784554730790:with|pipe", "rsi_value", 4);
    expect(fetchMock.mock.calls[0][0]).toBe(
      "/api/runs/US500-1784554730790%3Awith%7Cpipe/slices?feature=rsi_value&buckets=4"
    );
  });

  it("passes a complete trade row through getTrades unchanged", async () => {
    const trade = completeTrade();
    mockFetch([trade]);
    const rows = await getTrades("run-1", "all");
    expect(rows).toEqual([trade]);
  });

  it("passes a complete trade row through getTrade unchanged", async () => {
    const trade = completeTrade();
    mockFetch(trade);
    const result = await getTrade(trade.id);
    expect(result).toEqual(trade);
  });

  it.each([
    "entry_time",
    "exit_time",
    "direction",
    "entry_price",
    "exit_price",
    "stop_price",
    "target_price",
    "pnl",
    "net_pnl",
    "r_multiple",
    "is_win",
  ] as (keyof TradeSummary)[])(
    "rejects getTrades when a row is missing required field %s",
    async (field) => {
      const trade = completeTrade({ [field]: null } as Partial<Trade>);
      mockFetch([trade]);
      await expect(getTrades("run-1", "all")).rejects.toBeInstanceOf(ApiError);
    }
  );

  it("rejects getTrade when the row is missing a required field", async () => {
    const trade = completeTrade({ direction: undefined } as unknown as Partial<Trade>);
    mockFetch(trade);
    await expect(getTrade(trade.id)).rejects.toBeInstanceOf(ApiError);
  });

  it("rejects getTrade when bars_json is missing", async () => {
    const trade = completeTrade({ bars_json: null } as unknown as Partial<Trade>);
    mockFetch(trade);
    await expect(getTrade(trade.id)).rejects.toBeInstanceOf(ApiError);
  });

  it("posts a mark as JSON", async () => {
    const fetchMock = mockFetch({ signal_key: "k", kind: "T1", bar_ts: "ts", created_at: "now" });
    await postMark("k", "T1", "ts");
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/marks");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body)).toEqual({ signal_key: "k", kind: "T1", bar_ts: "ts" });
  });

  it("deletes a mark via query parameters, not a body", async () => {
    const fetchMock = mockFetch({ deleted: 1 });
    await deleteMark("US500|2025-03-04T14:30:00Z|LONG", "T1");
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/marks?signal_key=US500%7C2025-03-04T14%3A30%3A00Z%7CLONG&kind=T1");
    expect(init.method).toBe("DELETE");
    expect(init.body).toBeUndefined();
  });

  it("throws ApiError on a non-2xx response", async () => {
    mockFetch({ error: "unknown kind" }, 400);
    await expect(postMark("k", "T1", "ts")).rejects.toBeInstanceOf(ApiError);
  });

  it("parses bars_json into bar objects", () => {
    const trade = { bars_json: '[{"t":1,"o":2,"h":3,"l":1,"c":2}]' } as Trade;
    expect(getBars(trade)).toEqual([{ t: 1, o: 2, h: 3, l: 1, c: 2 }]);
  });
});
