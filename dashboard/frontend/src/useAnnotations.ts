import { useCallback, useEffect, useState } from "react";
import type { Flag, MarkKind } from "../../shared/vocab";
import * as api from "./api";
import type { Mark } from "./types";

/**
 * Flags and marks for every trade, keyed by signal_key. Writes are optimistic:
 * state changes immediately and reverts if the API call fails, so tapping stays
 * instant on a phone without risking a silent divergence from D1.
 */
export function useAnnotations() {
  const [flags, setFlags] = useState<Map<string, Flag>>(new Map());
  const [marks, setMarks] = useState<Map<string, Mark[]>>(new Map());
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    Promise.all([api.getFeedback(), api.getMarks()])
      .then(([fb, mk]) => {
        if (!live) return;
        setFlags(new Map(fb.filter((f) => f.flag).map((f) => [f.signal_key, f.flag as Flag])));
        const byKey = new Map<string, Mark[]>();
        for (const m of mk) byKey.set(m.signal_key, [...(byKey.get(m.signal_key) ?? []), m]);
        setMarks(byKey);
      })
      .catch((e: unknown) => live && setError(String(e)))
      .finally(() => live && setLoading(false));
    return () => {
      live = false;
    };
  }, []);

  const setFlag = useCallback((signalKey: string, flag: Flag) => {
    let previous: Flag | undefined;
    setFlags((prev) => {
      previous = prev.get(signalKey);
      const next = new Map(prev);
      next.set(signalKey, flag);
      return next;
    });
    api.postFeedback(signalKey, flag).catch((e: unknown) => {
      setError(String(e));
      setFlags((prev) => {
        const next = new Map(prev);
        if (previous === undefined) next.delete(signalKey);
        else next.set(signalKey, previous);
        return next;
      });
    });
  }, []);

  const toggleMark = useCallback(
    (signalKey: string, kind: MarkKind, barIso: string) => {
      // The removing/placing decision (and which API call to fire) must be resolved
      // synchronously against the latest known state here, not inside the setMarks
      // updater below: that updater can run after this function has already moved on
      // to the `if (removing)` branch, which would make the branch see a stale default.
      const previous = marks.get(signalKey) ?? [];
      const existing = previous.find((m) => m.kind === kind);
      const removing = existing?.bar_ts === barIso;
      const withoutKind = previous.filter((m) => m.kind !== kind);
      const nextForKey = removing
        ? withoutKind
        : [
            ...withoutKind,
            { id: "optimistic", signal_key: signalKey, kind, bar_ts: barIso, created_at: "" },
          ];

      setMarks((prev) => {
        const next = new Map(prev);
        next.set(signalKey, nextForKey);
        return next;
      });

      const revert = (e: unknown) => {
        setError(String(e));
        setMarks((prev) => {
          const next = new Map(prev);
          next.set(signalKey, previous);
          return next;
        });
      };

      if (removing) api.deleteMark(signalKey, kind).catch(revert);
      else api.postMark(signalKey, kind, barIso).catch(revert);
    },
    [marks]
  );

  return { flags, marks, setFlag, toggleMark, loading, error };
}
