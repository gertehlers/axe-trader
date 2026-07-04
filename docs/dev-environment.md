# Dev Environment & Session Startup

Operational gotchas for running this repo in an ephemeral (web/cloud) session, so a fresh session
starts clean instead of rediscovering them. Read alongside `CLAUDE.md` (Build & Run) and
`TODO.md`.

## 1. JDK: project targets 26, containers ship 21

`pom.xml` sets `<java.version>26</java.version>` (Spring Boot 4 / Java 26). The web-session
container currently ships **JDK 21 only** — `./mvnw` then fails at compile with:

```
error: release version 26 not supported
```

**Workaround for local build/test verification** — override the release on the command line
(the pom stays untouched; the backtest/strategy code is JDK-version-agnostic — records, `var`,
enhanced `switch` all compile on 21, and no 22–26-only language feature is used today):

```bash
./mvnw test -Djava.version=21
```

Backtest *numbers* are identical under 21 vs 26 (same bytecode semantics), so this is valid for
strategy validation. **Do not commit a pom downgrade.** The durable fix is to install a JDK 26 in
the container (e.g. via a SessionStart hook / setup script) — see `TODO.md`.

## 2. History DB is not present on a fresh clone — decompress it first

`data/axe-trader.sqlite` is **gitignored** (it exceeds the 100 MB push limit); only the compressed
`data/axe-trader.sqlite.gz` snapshot is committed. `DatabaseBootstrap.ensureDatabase()` restores it
— but it is only wired into `AxeTraderApplication.main()`, so it **does NOT run under
`@SpringBootTest`**. Any test that reads history (`BacktestRunnerTest`, `ConfluenceSweepTest`, …)
needs the raw file present first:

```bash
gzip -dc data/axe-trader.sqlite.gz > data/axe-trader.sqlite   # ~32 MB gz -> ~95 MB sqlite
```

Gitignored, so it will not dirty the working tree. Do this once per fresh container.

## 3. Running the strategy sweep harness

`ConfluenceSweepTest` is skipped in a normal `mvnw test` run (gated by `-Dsweep=true`). To run it:

```bash
# In-sample (default window: Dec 2024 -> Dec 2025), ~25s:
./mvnw test -Dtest=ConfluenceSweepTest -Dsweep=true -Djava.version=21

# Out-of-sample validation (Jan -> May 2026) — run ONCE, never tune against it:
./mvnw test -Dtest=ConfluenceSweepTest -Dsweep=true -Djava.version=21 \
    -Dsweep.from=2026-01-01T00:00:00Z -Dsweep.to=2026-05-02T00:00:00Z

# Persist each run's trades+features into experiments/experiments.sqlite:
#   add -Dsweep.persist=true   (query with: python3 experiments/query.py ...)
```

The avg bid/ask spread is auto-computed from the loaded bars (~0.19 pts in-sample, ~0.55 pts
out-of-sample) and subtracted once per round trip for the "net" columns. The `$net/trade` /
`$net/day` columns translate points to money via `backtest.contract.value-per-point`
(default 1.0 = US500 CFD ~$1/pt).

## 4. Backtest fill realism (what the "net" numbers now mean)

As of the 2026-07-04 pnl audit, exits are modeled **intrabar** by `BacktestRunner.intrabarExit`:
the stop/target bracket is posted at entry (`entry ± multiple × ATR-at-entry`) and fills **at the
level** on the first bar whose high/low touches it — with a **conservative stop-wins tie-break**
when one bar spans both levels. This replaced the old ta4j close-based rules, which never stopped
out on a bar that pierced the stop but closed back inside, and filled past the level on gap bars.
Entries still fill at the next bar's close on mid prices; one full spread per round trip covers the
mid→bid/ask correction. See `docs/observability-and-exits-design.md` §2 for the audit findings.

## 5. Proxy / TLS

`JAVA_TOOL_OPTIONS` is pre-set with the session truststore and HTTPS proxy. Don't override or unset
it; if a tool hits a TLS/proxy error see `/root/.ccr/README.md`.
