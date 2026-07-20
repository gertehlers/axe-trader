import { useState } from "react";

export type Tab = "trades" | "overview";

export default function App() {
  const [tab, setTab] = useState<Tab>("trades");

  return (
    <div className="app">
      <main className="panel">
        {tab === "trades" ? <p>Trades</p> : <p>Overview</p>}
      </main>
      <nav className="tabbar" role="tablist">
        <button role="tab" aria-selected={tab === "overview"} onClick={() => setTab("overview")}>
          Overview
        </button>
        <button role="tab" aria-selected={tab === "trades"} onClick={() => setTab("trades")}>
          Trades
        </button>
      </nav>
    </div>
  );
}
