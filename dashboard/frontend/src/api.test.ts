import { describe, it, expect, vi, afterEach } from "vitest";
import { getTrades, postMark, deleteMark, ApiError, getBars } from "./api";
import type { Trade } from "./types";

function mockFetch(body: unknown, status = 200) {
  const fn = vi.fn().mockResolvedValue(
    new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } })
  );
  vi.stubGlobal("fetch", fn);
  return fn;
}

afterEach(() => vi.unstubAllGlobals());

describe("api client", () => {
  it("requests the filtered trade list for a run", async () => {
    const fetchMock = mockFetch([]);
    await getTrades("run-1", "losers");
    expect(fetchMock.mock.calls[0][0]).toBe("/api/runs/run-1/trades?filter=losers");
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
