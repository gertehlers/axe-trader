import { StrictMode } from "react";
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

  it("places then removes a mark correctly under StrictMode's double-invoked updaters", async () => {
    vi.spyOn(api, "postMark").mockImplementation(async (signal_key, kind, bar_ts) => ({
      id: "m1",
      signal_key,
      kind,
      bar_ts,
      created_at: "",
    }));
    vi.spyOn(api, "deleteMark").mockResolvedValue({ deleted: 1 });

    const { result } = renderHook(() => useAnnotations(), {
      wrapper: ({ children }) => <StrictMode>{children}</StrictMode>,
    });
    await waitFor(() => expect(result.current.loading).toBe(false));

    await act(async () => {
      result.current.toggleMark(KEY, "T1", "2025-03-04T16:10:00Z"); // place
    });
    expect(result.current.marks.get(KEY)).toEqual([
      expect.objectContaining({ kind: "T1", bar_ts: "2025-03-04T16:10:00Z" }),
    ]);

    await act(async () => {
      result.current.toggleMark(KEY, "T1", "2025-03-04T16:10:00Z"); // same kind, same bar → removes
    });
    expect(result.current.marks.get(KEY) ?? []).toEqual([]);
    expect(api.deleteMark).toHaveBeenCalledWith(KEY, "T1");
  });

  it("keeps both marks when two different kinds are toggled within the same tick", async () => {
    vi.spyOn(api, "postMark").mockImplementation(async (signal_key, kind, bar_ts) => ({
      id: `m-${kind}`,
      signal_key,
      kind,
      bar_ts,
      created_at: "",
    }));

    const { result } = renderHook(() => useAnnotations());
    await waitFor(() => expect(result.current.loading).toBe(false));

    // Both calls fire from the same closure, in the same tick — a double tap, or
    // two memoized children each holding their own toggleMark reference.
    await act(async () => {
      result.current.toggleMark(KEY, "T1", "2025-03-04T16:10:00Z");
      result.current.toggleMark(KEY, "T2", "2025-03-04T16:15:00Z");
    });

    expect(result.current.marks.get(KEY)).toHaveLength(2);
    expect(result.current.marks.get(KEY)).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ kind: "T1", bar_ts: "2025-03-04T16:10:00Z" }),
        expect.objectContaining({ kind: "T2", bar_ts: "2025-03-04T16:15:00Z" }),
      ])
    );
    expect(api.postMark).toHaveBeenCalledWith(KEY, "T1", "2025-03-04T16:10:00Z");
    expect(api.postMark).toHaveBeenCalledWith(KEY, "T2", "2025-03-04T16:15:00Z");
  });

  it("does not let a stale flag revert clobber a newer write for the same key", async () => {
    let rejectChop!: (e: unknown) => void;
    const chopPromise = new Promise((_resolve, reject) => {
      rejectChop = reject;
    });
    vi.spyOn(api, "postFeedback").mockImplementation(async (signal_key, flag) => {
      if (flag === "chop") return chopPromise as never;
      return { id: "f2", signal_key, flag, note: null, created_at: "" };
    });

    const { result } = renderHook(() => useAnnotations());
    await waitFor(() => expect(result.current.loading).toBe(false));

    act(() => {
      result.current.setFlag(KEY, "chop"); // left in flight
    });
    await act(async () => {
      result.current.setFlag(KEY, "good-signal"); // succeeds, becomes authoritative
    });
    expect(result.current.flags.get(KEY)).toBe("good-signal");

    await act(async () => {
      rejectChop(new api.ApiError(500, "boom"));
      await chopPromise.catch(() => {});
    });

    expect(result.current.flags.get(KEY)).toBe("good-signal");
  });

  it("does not let a stale mark revert clobber a newer write for the same kind", async () => {
    let rejectFirst!: (e: unknown) => void;
    const firstPromise = new Promise((_resolve, reject) => {
      rejectFirst = reject;
    });
    vi.spyOn(api, "postMark").mockImplementation(async (signal_key, kind, bar_ts) => {
      if (bar_ts === "2025-03-04T16:10:00Z") return firstPromise as never;
      return { id: "m2", signal_key, kind, bar_ts, created_at: "" };
    });
    vi.spyOn(api, "deleteMark").mockResolvedValue({ deleted: 1 });

    const { result } = renderHook(() => useAnnotations());
    await waitFor(() => expect(result.current.loading).toBe(false));

    act(() => {
      result.current.toggleMark(KEY, "T1", "2025-03-04T16:10:00Z"); // left in flight
    });
    await act(async () => {
      result.current.toggleMark(KEY, "T1", "2025-03-04T16:20:00Z"); // moves, succeeds
    });
    expect(result.current.marks.get(KEY)).toEqual([
      expect.objectContaining({ kind: "T1", bar_ts: "2025-03-04T16:20:00Z" }),
    ]);

    await act(async () => {
      rejectFirst(new api.ApiError(500, "boom"));
      await firstPromise.catch(() => {});
    });

    expect(result.current.marks.get(KEY)).toEqual([
      expect.objectContaining({ kind: "T1", bar_ts: "2025-03-04T16:20:00Z" }),
    ]);
  });

  it("clears a stale error once a subsequent write succeeds", async () => {
    vi.spyOn(api, "postFeedback")
      .mockRejectedValueOnce(new api.ApiError(500, "boom"))
      .mockResolvedValueOnce({ id: "f1", signal_key: KEY, flag: "chop", note: null, created_at: "" });

    const { result } = renderHook(() => useAnnotations());
    await waitFor(() => expect(result.current.loading).toBe(false));

    await act(async () => {
      result.current.setFlag(KEY, "chop");
    });
    await waitFor(() => expect(result.current.error).toBeTruthy());

    await act(async () => {
      result.current.setFlag(KEY, "good-signal");
    });
    expect(result.current.error).toBeNull();
  });

  it("does not let an ABA flag revert (chop -> good-signal -> chop) erase the current value", async () => {
    // chop (fails) -> good-signal (succeeds) -> chop (succeeds). A value-equality check on
    // revert can't tell the failed first write's "chop" apart from the third write's "chop";
    // only a sequence number can.
    let rejectFirst!: (e: unknown) => void;
    const firstPromise = new Promise((_resolve, reject) => {
      rejectFirst = reject;
    });
    // First call uses the deferred promise; subsequent calls (including the later "chop")
    // resolve immediately. Track call count explicitly rather than relying on flag identity,
    // since the first and third calls both write "chop".
    let callCount = 0;
    vi.spyOn(api, "postFeedback").mockImplementation(async (signal_key, flag) => {
      callCount += 1;
      if (callCount === 1) return firstPromise as never;
      return { id: `f-${callCount}`, signal_key, flag, note: null, created_at: "" };
    });

    const { result } = renderHook(() => useAnnotations());
    await waitFor(() => expect(result.current.loading).toBe(false));

    act(() => {
      result.current.setFlag(KEY, "chop"); // #1, left in flight, will fail
    });
    await act(async () => {
      result.current.setFlag(KEY, "good-signal"); // #2, succeeds
    });
    await act(async () => {
      result.current.setFlag(KEY, "chop"); // #3, succeeds — same value as #1, different write
    });
    expect(result.current.flags.get(KEY)).toBe("chop");

    await act(async () => {
      rejectFirst(new api.ApiError(500, "boom"));
      await firstPromise.catch(() => {});
    });

    expect(result.current.flags.get(KEY)).toBe("chop");
  });

  it("does not let an ABA mark revert (T1@bar1 -> T1@bar2 -> T1@bar1) erase the current entry", async () => {
    let rejectFirst!: (e: unknown) => void;
    const firstPromise = new Promise((_resolve, reject) => {
      rejectFirst = reject;
    });
    let callCount = 0;
    vi.spyOn(api, "postMark").mockImplementation(async (signal_key, kind, bar_ts) => {
      callCount += 1;
      if (callCount === 1) return firstPromise as never;
      return { id: `m-${callCount}`, signal_key, kind, bar_ts, created_at: "" };
    });

    const { result } = renderHook(() => useAnnotations());
    await waitFor(() => expect(result.current.loading).toBe(false));

    act(() => {
      result.current.toggleMark(KEY, "T1", "2025-03-04T16:10:00Z"); // #1, in flight, will fail
    });
    await act(async () => {
      result.current.toggleMark(KEY, "T1", "2025-03-04T16:20:00Z"); // #2, moves, succeeds
    });
    await act(async () => {
      result.current.toggleMark(KEY, "T1", "2025-03-04T16:10:00Z"); // #3, moves back, succeeds
    });
    expect(result.current.marks.get(KEY)).toEqual([
      expect.objectContaining({ kind: "T1", bar_ts: "2025-03-04T16:10:00Z" }),
    ]);

    await act(async () => {
      rejectFirst(new api.ApiError(500, "boom"));
      await firstPromise.catch(() => {});
    });

    expect(result.current.marks.get(KEY)).toEqual([
      expect.objectContaining({ kind: "T1", bar_ts: "2025-03-04T16:10:00Z" }),
    ]);
  });

  it("documents accepted limitation (a): two same-slot writes that both fail, settling out of issue order, leave state holding a value the server never had", async () => {
    // Only the most-recently-*issued* write for a slot ever has standing to revert (see
    // writeOptimistic) — settle order doesn't change that. So when #2 (the later write) reverts,
    // it restores to whatever was in place right before #2 fired: #1's optimistic value. But #1
    // itself failed too, so the server never saw "chop" either — the hook ends up holding a
    // value the server has neither confirmed. This is accepted, not fixed by Change 1.
    let rejectFirst!: (e: unknown) => void;
    let rejectSecond!: (e: unknown) => void;
    const firstPromise = new Promise((_resolve, reject) => {
      rejectFirst = reject;
    });
    const secondPromise = new Promise((_resolve, reject) => {
      rejectSecond = reject;
    });
    let callCount = 0;
    vi.spyOn(api, "postFeedback").mockImplementation(async () => {
      callCount += 1;
      if (callCount === 1) return firstPromise as never;
      return secondPromise as never;
    });

    const { result } = renderHook(() => useAnnotations());
    await waitFor(() => expect(result.current.loading).toBe(false));

    act(() => {
      result.current.setFlag(KEY, "chop"); // #1, in flight, will fail
    });
    act(() => {
      result.current.setFlag(KEY, "good-signal"); // #2, in flight, will fail
    });
    expect(result.current.flags.get(KEY)).toBe("good-signal");

    // Settle out of issue order: #2 (the later write) settles first.
    await act(async () => {
      rejectSecond(new api.ApiError(500, "boom"));
      await secondPromise.catch(() => {});
    });
    // #2 has standing (it's still the latest-issued write for this slot) and reverts to #1's
    // optimistic "chop" — never confirmed by the server, since #1 also fails.
    expect(result.current.flags.get(KEY)).toBe("chop");

    await act(async () => {
      rejectFirst(new api.ApiError(500, "boom"));
      await firstPromise.catch(() => {});
    });
    // #1 no longer has standing (a later write — #2 — already superseded it), so its failure is
    // a no-op. Final state: "chop", a value the server never confirmed for either write.
    expect(result.current.flags.get(KEY)).toBe("chop");
  });

  it("documents accepted limitation (b): a mark removed locally can resurface if a concurrent load's stale GET still carries the pre-removal row", async () => {
    // The load resolves per (signal_key, kind) after Change 1 — but that only decides which
    // *kind* wins per key. It cannot tell "this kind was never touched locally" apart from "this
    // kind was touched locally and then removed back to nothing" — both look identical (the kind
    // is simply absent from the local array), so a stale GET response for the removed kind still
    // wins. Change 1 narrows this: before the fix, a locally-removed kind sharing a signal_key
    // with an untouched local kind was masked entirely (the whole loaded key was discarded in
    // favor of the local array). After the fix, the untouched kind is correctly preserved, but
    // the removed kind's stale row can resurface — this scenario didn't clearly manifest before.
    let resolveMarks!: (rows: Awaited<ReturnType<typeof api.getMarks>>) => void;
    const marksPromise = new Promise<Awaited<ReturnType<typeof api.getMarks>>>((resolve) => {
      resolveMarks = resolve;
    });
    vi.spyOn(api, "getFeedback").mockResolvedValue([]);
    vi.spyOn(api, "getMarks").mockReturnValue(marksPromise);
    vi.spyOn(api, "postMark").mockImplementation(async (signal_key, kind, bar_ts) => ({
      id: "m-local",
      signal_key,
      kind,
      bar_ts,
      created_at: "",
    }));
    vi.spyOn(api, "deleteMark").mockResolvedValue({ deleted: 1 });

    const { result } = renderHook(() => useAnnotations());
    expect(result.current.loading).toBe(true);

    // Place T2 (stays), place T1, then remove T1 — all locally, before the load resolves.
    await act(async () => {
      result.current.toggleMark(KEY, "T2", "2025-03-04T16:20:00Z");
    });
    await act(async () => {
      result.current.toggleMark(KEY, "T1", "2025-03-04T16:10:00Z");
    });
    await act(async () => {
      result.current.toggleMark(KEY, "T1", "2025-03-04T16:10:00Z"); // same kind+bar → removes
    });
    expect(result.current.marks.get(KEY)).toEqual([expect.objectContaining({ kind: "T2" })]);

    // The load's GET was issued before any of this happened, so its stale snapshot still
    // reports the pre-removal T1 row alongside T2.
    await act(async () => {
      resolveMarks([
        { id: "m1", signal_key: KEY, kind: "T1", bar_ts: "2025-03-04T16:10:00Z", created_at: "" },
        { id: "m2", signal_key: KEY, kind: "T2", bar_ts: "2025-03-04T16:20:00Z", created_at: "" },
      ]);
      await marksPromise;
    });
    await waitFor(() => expect(result.current.loading).toBe(false));

    // T1 resurfaces from the stale load even though it was removed locally; T2 is unaffected.
    // Pinning today's (post-Change-1) behavior — see comment above re: narrowing.
    expect(result.current.marks.get(KEY)).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ kind: "T1", bar_ts: "2025-03-04T16:10:00Z" }),
        expect.objectContaining({ kind: "T2", bar_ts: "2025-03-04T16:20:00Z" }),
      ])
    );
    expect(result.current.marks.get(KEY)).toHaveLength(2);
  });

  it("keeps a flag written before the initial load resolves, even when the loaded rows omit it", async () => {
    let resolveFeedback!: (rows: Awaited<ReturnType<typeof api.getFeedback>>) => void;
    const feedbackPromise = new Promise<Awaited<ReturnType<typeof api.getFeedback>>>((resolve) => {
      resolveFeedback = resolve;
    });
    vi.spyOn(api, "getFeedback").mockReturnValue(feedbackPromise);
    vi.spyOn(api, "getMarks").mockResolvedValue([]);
    vi.spyOn(api, "postFeedback").mockResolvedValue({
      id: "f1",
      signal_key: KEY,
      flag: "chop",
      note: null,
      created_at: "",
    });

    const { result } = renderHook(() => useAnnotations());
    expect(result.current.loading).toBe(true);

    await act(async () => {
      result.current.setFlag(KEY, "chop"); // issued while the load is still pending
    });
    expect(result.current.flags.get(KEY)).toBe("chop");

    await act(async () => {
      resolveFeedback([]); // the GET was issued before the POST landed, so it doesn't know about it
      await feedbackPromise;
    });
    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.flags.get(KEY)).toBe("chop");
  });

  it("keeps a mark written before the initial load resolves, even when the loaded rows omit it", async () => {
    let resolveMarks!: (rows: Awaited<ReturnType<typeof api.getMarks>>) => void;
    const marksPromise = new Promise<Awaited<ReturnType<typeof api.getMarks>>>((resolve) => {
      resolveMarks = resolve;
    });
    vi.spyOn(api, "getFeedback").mockResolvedValue([]);
    vi.spyOn(api, "getMarks").mockReturnValue(marksPromise);
    vi.spyOn(api, "postMark").mockImplementation(async (signal_key, kind, bar_ts) => ({
      id: "m1",
      signal_key,
      kind,
      bar_ts,
      created_at: "",
    }));

    const { result } = renderHook(() => useAnnotations());
    expect(result.current.loading).toBe(true);

    await act(async () => {
      result.current.toggleMark(KEY, "T1", "2025-03-04T16:10:00Z");
    });
    expect(result.current.marks.get(KEY)).toEqual([
      expect.objectContaining({ kind: "T1", bar_ts: "2025-03-04T16:10:00Z" }),
    ]);

    await act(async () => {
      resolveMarks([]);
      await marksPromise;
    });
    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.marks.get(KEY)).toEqual([
      expect.objectContaining({ kind: "T1", bar_ts: "2025-03-04T16:10:00Z" }),
    ]);
  });

  it("keeps a loaded mark of a different kind when a local write for another kind is concurrent (regression)", async () => {
    let resolveMarks!: (rows: Awaited<ReturnType<typeof api.getMarks>>) => void;
    const marksPromise = new Promise<Awaited<ReturnType<typeof api.getMarks>>>((resolve) => {
      resolveMarks = resolve;
    });
    vi.spyOn(api, "getFeedback").mockResolvedValue([]);
    vi.spyOn(api, "getMarks").mockReturnValue(marksPromise);
    vi.spyOn(api, "postMark").mockImplementation(async (signal_key, kind, bar_ts) => ({
      id: "m-local",
      signal_key,
      kind,
      bar_ts,
      created_at: "",
    }));

    const { result } = renderHook(() => useAnnotations());
    expect(result.current.loading).toBe(true);

    // Local write for T1 lands before the load resolves — a different bar than whatever the
    // server eventually reports for T1.
    await act(async () => {
      result.current.toggleMark(KEY, "T1", "2025-03-04T16:30:00Z");
    });
    expect(result.current.marks.get(KEY)).toEqual([
      expect.objectContaining({ kind: "T1", bar_ts: "2025-03-04T16:30:00Z" }),
    ]);

    // The load resolves with T1 (stale, different bar) AND T2 (never touched locally) for the
    // same signal_key.
    await act(async () => {
      resolveMarks([
        { id: "m1", signal_key: KEY, kind: "T1", bar_ts: "2025-03-04T16:10:00Z", created_at: "" },
        { id: "m2", signal_key: KEY, kind: "T2", bar_ts: "2025-03-04T16:20:00Z", created_at: "" },
      ]);
      await marksPromise;
    });
    await waitFor(() => expect(result.current.loading).toBe(false));

    // Local wins for T1 (the kind it touched); T2, which the local write never touched, must
    // survive the merge rather than being dropped because the key was present locally.
    expect(result.current.marks.get(KEY)).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ kind: "T1", bar_ts: "2025-03-04T16:30:00Z" }),
        expect.objectContaining({ kind: "T2", bar_ts: "2025-03-04T16:20:00Z" }),
      ])
    );
    expect(result.current.marks.get(KEY)).toHaveLength(2);
  });

  it("loads existing feedback and marks on mount, grouped by signal_key", async () => {
    vi.spyOn(api, "getFeedback").mockResolvedValue([
      { id: "f1", signal_key: KEY, flag: "chop", note: null, created_at: "" },
      { id: "f2", signal_key: "OTHER", flag: null, note: "no flag set yet", created_at: "" },
    ]);
    vi.spyOn(api, "getMarks").mockResolvedValue([
      { id: "m1", signal_key: KEY, kind: "T1", bar_ts: "2025-03-04T16:10:00Z", created_at: "" },
      { id: "m2", signal_key: KEY, kind: "T2", bar_ts: "2025-03-04T16:20:00Z", created_at: "" },
      { id: "m3", signal_key: "OTHER", kind: "T1", bar_ts: "2025-03-04T17:00:00Z", created_at: "" },
    ]);

    const { result } = renderHook(() => useAnnotations());
    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.flags.get(KEY)).toBe("chop");
    expect(result.current.flags.has("OTHER")).toBe(false); // null flag excluded

    expect(result.current.marks.get(KEY)).toEqual([
      expect.objectContaining({ id: "m1", kind: "T1", bar_ts: "2025-03-04T16:10:00Z" }),
      expect.objectContaining({ id: "m2", kind: "T2", bar_ts: "2025-03-04T16:20:00Z" }),
    ]);
    expect(result.current.marks.get("OTHER")).toEqual([
      expect.objectContaining({ id: "m3", kind: "T1", bar_ts: "2025-03-04T17:00:00Z" }),
    ]);
  });
});
