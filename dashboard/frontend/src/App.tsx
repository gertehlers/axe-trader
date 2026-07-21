import { useEffect, useState } from "react";
import * as api from "./api";
import type { Run } from "./types";
import { useAnnotations } from "./useAnnotations";
import Overview from "./components/Overview";
import TradeDeck from "./components/TradeDeck";

export type Tab = "trades" | "overview";

const errorMessage = (e: unknown): string => (e instanceof Error ? e.message : String(e));

export default function App() {
  const [tab, setTab] = useState<Tab>("trades");
  const [runs, setRuns] = useState<Run[]>([]);
  const [runId, setRunId] = useState<string>("");
  const [runsError, setRunsError] = useState<string | null>(null);
  const { flags, marks, setFlag, toggleMark, error } = useAnnotations();

  useEffect(() => {
    let live = true;
    api
      .getRuns()
      .then((rows) => {
        if (!live) return;
        setRuns(rows);
        if (rows.length > 0) setRunId(rows[0].id); // API returns newest first
      })
      .catch((e: unknown) => {
        if (!live) return;
        setRunsError(errorMessage(e));
      });
    return () => {
      live = false;
    };
  }, []);

  const run = runs.find((r) => r.id === runId);

  return (
    <div className="app">
      <header className="app-head">
        <label>
          Run
          <select value={runId} onChange={(e) => setRunId(e.target.value)}>
            {runs.map((r) => (
              <option key={r.id} value={r.id}>
                {r.label ?? r.id}
              </option>
            ))}
          </select>
        </label>
      </header>

      {error && <p className="error">{error}</p>}

      <main className="panel" role="tabpanel" id={`panel-${tab}`} aria-labelledby={`tab-${tab}`}>
        {runsError ? (
          <p className="error">Couldn't load runs: {runsError}</p>
        ) : !run ? (
          <p className="loading">Loading runs…</p>
        ) : tab === "trades" ? (
          <TradeDeck
            runId={run.id}
            flags={flags}
            marks={marks}
            onFlag={setFlag}
            onToggleMark={toggleMark}
          />
        ) : (
          <Overview run={run} />
        )}
      </main>

      <nav className="tabbar" role="tablist">
        <button
          role="tab"
          id="tab-overview"
          aria-controls="panel-overview"
          aria-selected={tab === "overview"}
          onClick={() => setTab("overview")}
        >
          Overview
        </button>
        <button
          role="tab"
          id="tab-trades"
          aria-controls="panel-trades"
          aria-selected={tab === "trades"}
          onClick={() => setTab("trades")}
        >
          Trades
        </button>
      </nav>
    </div>
  );
}
