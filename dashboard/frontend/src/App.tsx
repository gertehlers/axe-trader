import { useEffect, useState } from "react";
import * as api from "./api";
import type { DiscoveryReport, DiscoveryRun, Run } from "./types";
import { useAnnotations } from "./useAnnotations";
import Overview from "./components/Overview";
import TradeDeck from "./components/TradeDeck";
import DiscoveryOverview from "./components/DiscoveryOverview";

export type Tab = "trades" | "overview" | "discovery";

const errorMessage = (e: unknown): string => (e instanceof Error ? e.message : String(e));

export default function App() {
  const [tab, setTab] = useState<Tab>("trades");
  const [runs, setRuns] = useState<Run[]>([]);
  const [runId, setRunId] = useState<string>("");
  const [runsError, setRunsError] = useState<string | null>(null);
  const [runsLoaded, setRunsLoaded] = useState(false);
  const [discoveryRuns, setDiscoveryRuns] = useState<DiscoveryRun[]>([]);
  const [discoveryId, setDiscoveryId] = useState("");
  const [discovery, setDiscovery] = useState<DiscoveryReport | null>(null);
  const { flags, marks, setFlag, toggleMark, error } = useAnnotations();

  useEffect(() => {
    let live = true;
    api
      .getRuns()
      .then((rows) => {
        if (!live) return;
        setRuns(rows);
        if (rows.length > 0) setRunId(rows[0].id); // API returns newest first
        setRunsLoaded(true);
      })
      .catch((e: unknown) => {
        if (!live) return;
        setRunsError(errorMessage(e));
      });
    return () => {
      live = false;
    };
  }, []);

  useEffect(() => {
    api.getDiscoveryRuns().then((rows) => { setDiscoveryRuns(rows); if (rows[0]) setDiscoveryId(rows[0].id); }).catch(() => setDiscoveryRuns([]));
  }, []);
  useEffect(() => {
    if (!discoveryId) return;
    api.getDiscoveryRun(discoveryId).then(setDiscovery).catch(() => setDiscovery(null));
  }, [discoveryId]);

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
      {tab === "discovery" && discoveryRuns.length > 0 && <label className="discovery-select">Discovery run
        <select value={discoveryId} onChange={(e) => setDiscoveryId(e.target.value)}>{discoveryRuns.map((r) => <option key={r.id} value={r.id}>{r.instrument} · {r.window_to}</option>)}</select>
      </label>}

      {error && <p className="error">{error}</p>}

      {/* One panel element is swapped between tabs rather than two persistent panels, so its id
          must be stable: templating it off `tab` left the INACTIVE tab's aria-controls pointing
          at an id no longer in the DOM. Both tabs reference this single id. */}
      <main className="panel" role="tabpanel" id="panel" aria-labelledby={`tab-${tab}`}>
        {tab === "discovery" ? (
          discovery ? <DiscoveryOverview report={discovery} /> : <p className="empty">No discovery reports yet.</p>
        ) : runsError ? (
          <p className="error">Couldn't load runs: {runsError}</p>
        ) : runsLoaded && runs.length === 0 ? (
          <p className="empty">No runs yet. Export one with the dashboard exporter, then push it.</p>
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
          aria-controls="panel"
          aria-selected={tab === "overview"}
          onClick={() => setTab("overview")}
        >
          Overview
        </button>
        <button
          role="tab"
          id="tab-trades"
          aria-controls="panel"
          aria-selected={tab === "trades"}
          onClick={() => setTab("trades")}
        >
          Trades
        </button>
        <button role="tab" id="tab-discovery" aria-controls="panel" aria-selected={tab === "discovery"} onClick={() => setTab("discovery")}>Discovery</button>
      </nav>
    </div>
  );
}
