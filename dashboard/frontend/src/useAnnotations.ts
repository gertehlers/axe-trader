import { useCallback, useEffect, useRef, useState } from "react";
import type { Dispatch, MutableRefObject, SetStateAction } from "react";
import type { Flag, MarkKind } from "../../shared/vocab";
import * as api from "./api";
import type { Mark } from "./types";

/**
 * Flags and marks for every trade, keyed by signal_key. Writes are optimistic:
 * state changes immediately and reverts if the API call fails, so tapping stays
 * instant on a phone without risking a silent divergence from D1.
 */

type SetMap<V> = Dispatch<SetStateAction<Map<string, V>>>;

/** Every mark placed optimistically needs an id unique enough that a list keyed
 * by `Mark.id` never collides, even when two optimistic marks land under the
 * same signal_key in the same tick (before either is reconciled with the server). */
let optimisticIdSeq = 0;
const nextOptimisticId = () => `optimistic-${Date.now()}-${optimisticIdSeq++}`;

const errorMessage = (e: unknown): string => (e instanceof Error ? e.message : String(e));

/**
 * Shared shape for every optimistic write in this hook: compute the next per-key
 * value from whatever is *actually* in state (never from a precomputed snapshot),
 * fire the request, and on failure revert — but only if the value currently in
 * state for this key is still the one this call installed. If a newer write has
 * since changed it, that write is authoritative and the revert is a no-op.
 *
 * `apply`/`isInstalled`/`restore` are plain pure functions (no captured mutable
 * state), so they're safe to invoke from inside a React state updater, including
 * under React StrictMode's double-invocation of updaters.
 */
function writeOptimistic<V>(
  ref: MutableRefObject<Map<string, V>>,
  setState: SetMap<V>,
  setError: (message: string) => void,
  clearError: () => void,
  key: string,
  apply: (current: V | undefined) => V | undefined,
  isInstalled: (current: V | undefined) => boolean,
  restore: (current: V | undefined) => V | undefined,
  request: () => Promise<unknown>
) {
  // Synchronous ref bump: a second call in the same tick (e.g. two taps before
  // any re-render) must decide its own removing/placing branch against this
  // call's result, not a stale closure/state snapshot. The effect below keeps
  // the ref in sync with committed state for every case in between.
  const bumped = new Map(ref.current);
  const bumpedValue = apply(ref.current.get(key));
  if (bumpedValue === undefined) bumped.delete(key);
  else bumped.set(key, bumpedValue);
  ref.current = bumped;

  setState((prev) => {
    const value = apply(prev.get(key));
    const next = new Map(prev);
    if (value === undefined) next.delete(key);
    else next.set(key, value);
    return next;
  });

  request()
    .then(clearError)
    .catch((e: unknown) => {
      setError(errorMessage(e));
      setState((prev) => {
        if (!isInstalled(prev.get(key))) return prev; // superseded by a newer write; leave it alone
        const value = restore(prev.get(key));
        const next = new Map(prev);
        if (value === undefined) next.delete(key);
        else next.set(key, value);
        return next;
      });
    });
}

export function useAnnotations() {
  const [flags, setFlags] = useState<Map<string, Flag>>(new Map());
  const [marks, setMarks] = useState<Map<string, Mark[]>>(new Map());
  const [loading, setLoading] = useState(true);
  const [error, setErrorState] = useState<string | null>(null);

  // Mirror state into refs so writes can make synchronous decisions (which branch,
  // what to revert to) against the latest known value, without depending on a
  // closure captured at the last render.
  const flagsRef = useRef<Map<string, Flag>>(flags);
  const marksRef = useRef<Map<string, Mark[]>>(marks);
  useEffect(() => {
    flagsRef.current = flags;
  }, [flags]);
  useEffect(() => {
    marksRef.current = marks;
  }, [marks]);

  const setError = useCallback((message: string) => setErrorState(message), []);
  const clearError = useCallback(() => setErrorState(null), []);

  useEffect(() => {
    let live = true;
    Promise.all([api.getFeedback(), api.getMarks()])
      .then(([fb, mk]) => {
        if (!live) return;
        const flagMap = new Map(fb.filter((f) => f.flag).map((f) => [f.signal_key, f.flag as Flag]));
        setFlags(flagMap);
        flagsRef.current = flagMap;

        const byKey = new Map<string, Mark[]>();
        for (const m of mk) byKey.set(m.signal_key, [...(byKey.get(m.signal_key) ?? []), m]);
        setMarks(byKey);
        marksRef.current = byKey;
      })
      .catch((e: unknown) => live && setError(errorMessage(e)))
      .finally(() => live && setLoading(false));
    return () => {
      live = false;
    };
  }, [setError]);

  const setFlag = useCallback(
    (signalKey: string, flag: Flag) => {
      const previous = flagsRef.current.get(signalKey);
      writeOptimistic(
        flagsRef,
        setFlags,
        setError,
        clearError,
        signalKey,
        () => flag, // "setting replaces" — unconditional regardless of what's there
        (current) => current === flag,
        () => previous,
        () => api.postFeedback(signalKey, flag)
      );
    },
    [setError, clearError]
  );

  const toggleMark = useCallback(
    (signalKey: string, kind: MarkKind, barIso: string) => {
      // The removing/placing decision (and which API call to fire) must be resolved
      // synchronously here, against the ref mirror of the latest known marks — not
      // the `marks` state closure, which can be stale across two calls in one tick.
      const currentForKey = marksRef.current.get(signalKey) ?? [];
      const existing = currentForKey.find((m) => m.kind === kind);
      const removing = existing?.bar_ts === barIso;
      const newMark: Mark = {
        id: nextOptimisticId(),
        signal_key: signalKey,
        kind,
        bar_ts: barIso,
        created_at: "",
      };

      writeOptimistic(
        marksRef,
        setMarks,
        setError,
        clearError,
        signalKey,
        (current) => {
          const withoutKind = (current ?? []).filter((m) => m.kind !== kind);
          const nextForKey = removing ? withoutKind : [...withoutKind, newMark];
          return nextForKey.length === 0 ? undefined : nextForKey;
        },
        (current) => {
          const entry = (current ?? []).find((m) => m.kind === kind);
          const installedBarTs = removing ? undefined : barIso;
          return entry?.bar_ts === installedBarTs;
        },
        (current) => {
          const withoutKind = (current ?? []).filter((m) => m.kind !== kind);
          const restored = existing ? [...withoutKind, existing] : withoutKind;
          return restored.length === 0 ? undefined : restored;
        },
        () => (removing ? api.deleteMark(signalKey, kind) : api.postMark(signalKey, kind, barIso))
      );
    },
    [setError, clearError]
  );

  return { flags, marks, setFlag, toggleMark, loading, error };
}
