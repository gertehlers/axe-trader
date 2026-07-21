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

/** Monotonic clock for "which write is the latest for this slot" — see writeOptimistic. */
let writeSeqCounter = 0;

const errorMessage = (e: unknown): string => (e instanceof Error ? e.message : String(e));

/** Loaded rows first, then anything already known locally overrides them — used both for the
 * initial-load merge and for every write's ref bump, so "what's currently known for this key"
 * always means the same thing everywhere in this hook. */
function mergeKeepingOverrides<V>(base: Map<string, V>, overrides: Map<string, V>): Map<string, V> {
  const merged = new Map(base);
  for (const [k, v] of overrides) merged.set(k, v);
  return merged;
}

/** Same idea as mergeKeepingOverrides, but for marks: a `Mark[]` packs independent per-kind
 * sub-slots (see `slot: ${signalKey}::${kind}` in toggleMark), so taking the local array for a
 * key wholesale is wrong — it silently drops loaded marks of a kind the local write never
 * touched. Resolve at (signal_key, kind) granularity instead: for each key known locally, keep
 * loaded marks whose kind isn't present locally, then append the local marks — local still wins
 * per kind, but untouched kinds from the load survive. Keys known only locally, or only loaded,
 * pass through unchanged via the base clone / the loaded-marks fallback below. */
function mergeMarksKeepingOverrides(
  base: Map<string, Mark[]>,
  overrides: Map<string, Mark[]>
): Map<string, Mark[]> {
  const merged = new Map(base);
  for (const [key, localMarks] of overrides) {
    const localKinds = new Set(localMarks.map((m) => m.kind));
    const loadedMarks = merged.get(key) ?? [];
    merged.set(key, [...loadedMarks.filter((m) => !localKinds.has(m.kind)), ...localMarks]);
  }
  return merged;
}

interface OptimisticWriteArgs<V> {
  /** Mirror of the state map, kept in sync synchronously (not just via effect) so a second call
   * in the same tick sees this call's contribution immediately. */
  valueRef: MutableRefObject<Map<string, V>>;
  setState: SetMap<V>;
  /** Per-slot monotonic sequence numbers — see the writeOptimistic doc comment for why this
   * exists instead of comparing values. */
  seqRef: MutableRefObject<Map<string, number>>;
  /** The unit this write must still be the *latest* write for, to have standing to revert. For
   * flags this is the signal_key; for marks it's `signal_key::kind`, since a mark write only
   * ever owns one kind's slot and an unrelated kind changing concurrently must not affect it. */
  slot: string;
  setError: (message: string) => void;
  clearError: () => void;
  /** The key in the *state* map itself — always signal_key, for both flags and marks. */
  key: string;
  apply: (current: V | undefined) => V | undefined;
  restore: (current: V | undefined) => V | undefined;
  request: () => Promise<unknown>;
}

/**
 * Shared shape for every optimistic write in this hook: compute the next per-key value from
 * whatever is *actually* in state (never from a precomputed snapshot), fire the request, and on
 * failure revert — but only if this write is still the most recent one issued for its slot.
 *
 * That last check is a monotonic sequence number stamped at issue time, not a comparison of
 * values: a value-equality check can't distinguish "nothing has touched this slot since I wrote
 * it" from "something changed it away and it has since changed back to the same value" (ABA) —
 * e.g. chop → good-signal → chop, where the first chop's failed write must not revert the third
 * write's chop back to nothing just because the current value happens to match. A sequence
 * number sidesteps that: only the most-recently-*issued* write for a slot ever has standing to
 * revert it, regardless of what value is currently there or what order requests settle in.
 *
 * `apply`/`restore` are plain pure functions (no captured mutable state), so they're safe to
 * invoke from inside a React state updater, including under React StrictMode's
 * double-invocation of updaters. The sequence check itself never runs inside an updater — it's a
 * plain ref read, independent of the state map's contents.
 */
function writeOptimistic<V>(args: OptimisticWriteArgs<V>) {
  const { valueRef, setState, seqRef, slot, setError, clearError, key, apply, restore, request } = args;

  const mySeq = ++writeSeqCounter;
  seqRef.current.set(slot, mySeq);

  // Ref bump: synchronous, so a second call to writeOptimistic in the same tick (e.g. two taps
  // before any re-render) makes its own decision against this call's result, not a stale
  // snapshot. The effect in useAnnotations keeps the ref in sync with committed state for every
  // case in between (including after a revert — see below).
  const bumped = new Map(valueRef.current);
  const bumpedValue = apply(valueRef.current.get(key));
  if (bumpedValue === undefined) bumped.delete(key);
  else bumped.set(key, bumpedValue);
  valueRef.current = bumped;

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

      // A later write for this exact slot — whether it has settled yet or not — is authoritative;
      // this write no longer has standing to revert anything.
      if (seqRef.current.get(slot) !== mySeq) return;

      // Symmetric with the write path above: ref bump computed from the ref, state write
      // recomputed functionally from prev — never a precomputed snapshot assigned wholesale.
      const revertedRefValue = restore(valueRef.current.get(key));
      const revertedRef = new Map(valueRef.current);
      if (revertedRefValue === undefined) revertedRef.delete(key);
      else revertedRef.set(key, revertedRefValue);
      valueRef.current = revertedRef;

      setState((prev) => {
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

  // Mirror state into refs so writes can make synchronous decisions (which branch, what to
  // revert to) against the latest known value, without depending on a closure captured at the
  // last render.
  const flagsRef = useRef<Map<string, Flag>>(flags);
  const marksRef = useRef<Map<string, Mark[]>>(marks);
  useEffect(() => {
    flagsRef.current = flags;
  }, [flags]);
  useEffect(() => {
    marksRef.current = marks;
  }, [marks]);

  // Per-slot "latest write" sequence numbers — see writeOptimistic. Never read by a render, so
  // these are mutated in place rather than cloned.
  const flagsSeqRef = useRef<Map<string, number>>(new Map());
  const marksSeqRef = useRef<Map<string, number>>(new Map());

  const setError = useCallback((message: string) => setErrorState(message), []);
  const clearError = useCallback(() => setErrorState(null), []);

  useEffect(() => {
    let live = true;

    // Feedback and marks are independent tables behind independent endpoints, so they are loaded
    // independently. Loading them as one Promise.all meant either endpoint failing discarded the
    // OTHER one's rows too — with /api/marks 500ing, flags silently vanished as well and every
    // trade rendered unflagged, which reads as "my review verdicts are gone".
    //
    // In both branches: loaded rows can be stale by the time they arrive — a write issued before
    // this resolves (the hook doesn't gate setFlag/toggleMark on `loading`) may have already
    // landed on the server, in a POST the load's own GET was issued before. Merge rather than
    // overwrite: loaded rows first, then anything already known locally wins.
    const loadFeedback = api
      .getFeedback()
      .then((fb) => {
        if (!live) return;
        const loadedFlags = new Map(fb.filter((f) => f.flag).map((f) => [f.signal_key, f.flag as Flag]));
        flagsRef.current = mergeKeepingOverrides(loadedFlags, flagsRef.current);
        setFlags((prev) => mergeKeepingOverrides(loadedFlags, prev));
      })
      .catch((e: unknown) => live && setError(errorMessage(e)));

    const loadMarks = api
      .getMarks()
      .then((mk) => {
        if (!live) return;
        const loadedMarks = new Map<string, Mark[]>();
        for (const m of mk) loadedMarks.set(m.signal_key, [...(loadedMarks.get(m.signal_key) ?? []), m]);
        marksRef.current = mergeMarksKeepingOverrides(loadedMarks, marksRef.current);
        setMarks((prev) => mergeMarksKeepingOverrides(loadedMarks, prev));
      })
      .catch((e: unknown) => live && setError(errorMessage(e)));

    // allSettled, not all: `loading` must clear once both have finished either way, and both
    // chains already absorb their own rejection.
    void Promise.allSettled([loadFeedback, loadMarks]).then(() => live && setLoading(false));
    return () => {
      live = false;
    };
  }, [setError]);

  const setFlag = useCallback(
    (signalKey: string, flag: Flag) => {
      const previous = flagsRef.current.get(signalKey);
      writeOptimistic({
        valueRef: flagsRef,
        setState: setFlags,
        seqRef: flagsSeqRef,
        slot: signalKey,
        setError,
        clearError,
        key: signalKey,
        apply: () => flag, // "setting replaces" — unconditional regardless of what's there
        restore: () => previous,
        request: () => api.postFeedback(signalKey, flag),
      });
    },
    [setError, clearError]
  );

  const toggleMark = useCallback(
    (signalKey: string, kind: MarkKind, barIso: string) => {
      // The removing/placing decision (and which API call to fire) must be resolved
      // synchronously here, against the ref mirror of the latest known marks — not the `marks`
      // state closure, which can be stale across two calls in one tick.
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

      writeOptimistic({
        valueRef: marksRef,
        setState: setMarks,
        seqRef: marksSeqRef,
        // Per-kind, not per signal_key: an unrelated kind's write must not affect whether this
        // write still has standing to revert.
        slot: `${signalKey}::${kind}`,
        setError,
        clearError,
        key: signalKey,
        apply: (current) => {
          const withoutKind = (current ?? []).filter((m) => m.kind !== kind);
          const nextForKey = removing ? withoutKind : [...withoutKind, newMark];
          return nextForKey.length === 0 ? undefined : nextForKey;
        },
        restore: (current) => {
          const withoutKind = (current ?? []).filter((m) => m.kind !== kind);
          const restored = existing ? [...withoutKind, existing] : withoutKind;
          return restored.length === 0 ? undefined : restored;
        },
        request: () => (removing ? api.deleteMark(signalKey, kind) : api.postMark(signalKey, kind, barIso)),
      });
    },
    [setError, clearError]
  );

  return { flags, marks, setFlag, toggleMark, loading, error };
}
