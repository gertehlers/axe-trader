import { renderHook, act, waitFor } from "@testing-library/react";
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { useAnnotations } from "./useAnnotations";
import * as api from "./api";

const KEY = "US500|2025-03-04T16:00:00Z|LONG";

beforeEach(() => {
  vi.spyOn(api, "getFeedback").mockResolvedValue([]);
  vi.spyOn(api, "getMarks").mockResolvedValue([]);
});
afterEach(() => vi.restoreAllMocks());

describe("useAnnotations", () => {
  it("applies a flag immediately and keeps it when the request succeeds", async () => {
    vi.spyOn(api, "postFeedback").mockResolvedValue({
      id: "f1",
      signal_key: KEY,
      flag: "chop",
      note: null,
      created_at: "",
    });
    const { result } = renderHook(() => useAnnotations());
    await waitFor(() => expect(result.current.loading).toBe(false));

    await act(async () => {
      result.current.setFlag(KEY, "chop");
    });
    expect(result.current.flags.get(KEY)).toBe("chop");
  });

  it("reverts the flag when the request fails", async () => {
    vi.spyOn(api, "postFeedback").mockRejectedValue(new api.ApiError(500, "boom"));
    const { result } = renderHook(() => useAnnotations());
    await waitFor(() => expect(result.current.loading).toBe(false));

    await act(async () => {
      result.current.setFlag(KEY, "chop");
    });
    await waitFor(() => expect(result.current.flags.get(KEY)).toBeUndefined());
    expect(result.current.error).toBeTruthy();
  });

  it("places, moves and removes a mark", async () => {
    vi.spyOn(api, "postMark").mockImplementation(async (signal_key, kind, bar_ts) => ({
      id: "m1",
      signal_key,
      kind,
      bar_ts,
      created_at: "",
    }));
    vi.spyOn(api, "deleteMark").mockResolvedValue({ deleted: 1 });

    const { result } = renderHook(() => useAnnotations());
    await waitFor(() => expect(result.current.loading).toBe(false));

    await act(async () => {
      result.current.toggleMark(KEY, "T1", "2025-03-04T16:10:00Z");
    });
    expect(result.current.marks.get(KEY)).toEqual([
      expect.objectContaining({ kind: "T1", bar_ts: "2025-03-04T16:10:00Z" }),
    ]);

    // same kind, different bar → moves
    await act(async () => {
      result.current.toggleMark(KEY, "T1", "2025-03-04T16:20:00Z");
    });
    expect(result.current.marks.get(KEY)).toEqual([
      expect.objectContaining({ kind: "T1", bar_ts: "2025-03-04T16:20:00Z" }),
    ]);

    // same kind, same bar → removes
    await act(async () => {
      result.current.toggleMark(KEY, "T1", "2025-03-04T16:20:00Z");
    });
    expect(result.current.marks.get(KEY) ?? []).toEqual([]);
    expect(api.deleteMark).toHaveBeenCalledWith(KEY, "T1");
  });
});
