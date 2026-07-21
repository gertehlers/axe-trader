# axe-trader — Backlog

## Status
Phase 4 complete. Pre-MVP: priority is a stable monitor pipeline, then strategy accuracy.

North star (see `CLAUDE.md` → Trading Goals): 80%+ win rate, ~5 quality trades/day per instrument
(not scalping), reproducible via 5-pillar confluence, with each instrument tuned as its own
"personality" rather than one shared config.

---

## In Progress

- [ ] Validate MONITOR mode end-to-end (auth → WebSocket → SQLite writes confirmed)
- [ ] **Observability + exits round** — see `docs/observability-and-exits-design.md` for the full spec.
      - [x] (1) experiment SQLite DB + per-trade feature capture + `exit_reason`, backfilled.
        `experiments.sqlite` (gitignored, regenerate with `-Dsweep.persist=true`), queryable via
        `experiments/query.py`. 4 milestones backfilled (baseline, 2 pre-gate losers, final).
        **First lead (preliminary — pending pnl audit):** on the final candidate, longs entered
        *near* the trend EMA (0–1.5 ATR above) win 92% @ net +2.68, while extended entries (>10 ATR
        above, and the 3–4.7 ATR band) drag — i.e. "buy the dip near the EMA," don't chase extension.
        A `dist_to_trend_ema_atr` ceiling is a candidate filter once pnl is trusted.
      - [x] (2) **pnl audit + $ translation** DONE (2026-07-04) — sign ✅, spread ✅ (one full spread
        per round trip, not double-counted), fill=mid ✅ (corrected by spread). Found + fixed the
        bias: exits were close-based; now a fixed intrabar bracket (`BacktestRunner.intrabarExit`)
        fills **at the stop/target level** with a conservative stop-wins tie-break. Also fixed
        `rMultiple` (was pnl/ATR → now pnl/stop-distance). Added `backtest.contract.value-per-point`
        + `$net` sweep columns. **Re-validation falsified the promoted profile's expectancy** (see
        the scorecard below): win rate holds ~80% but net is **negative** under honest fills
        (IS −0.14, OOS −0.29 pts/trade). See `docs/observability-and-exits-design.md` §2.
      - [ ] (3) phone trade-review dashboard (self-describing SVG cards, 50 bars each side).
        **Delivery pivoted 2026-07-19 (artifact → Cloudflare + D1)** so phone *feedback* can sync
        back to the engine's hypothesis loop — an artifact is read-only and can't. Decisions locked
        with user: everything in D1 (runs + trades + feedback); feedback keyed on a stable
        `signal_key` (`instrument|entry_ts|direction`) so flags survive re-runs; Cloudflare Access
        (email-OTP) gates it; **no Java strategy changes** — one read-only `DashboardExporter` emits
        `run.json` (bars/stop/target aren't in `experiments.sqlite`), a Node/wrangler script dumb-
        pushes it to D1. Split into two plans:
        - **Spec:** `docs/superpowers/specs/2026-07-19-cloudflare-trade-review-dashboard-design.md`
          (supersedes `observability-and-exits-design.md` §3).
        - **Plan 1 (data pipeline): DONE 2026-07-20, deployed and live.**
          `docs/superpowers/plans/2026-07-19-trade-review-data-pipeline.md` — all 11 tasks.
          Worker <https://axe-trader-dashboard.g3tech.workers.dev> (free tier), D1
          `axe-trader-dashboard` (`fd847584-8fd9-427e-b869-9423a6c5b419`, WEUR), Cloudflare Access
          gating every route (unauthenticated → 302). The `emaCeil_3,0atr` run (102 trades, 88% win)
          is pushed to remote D1. Runbook + toolchain notes: `dashboard/DEPLOY.md`.
          Export a run with `./mvnw test -Dtest=ConfluenceSweepTest -Dsweep=true
          -Dsweep.exportDashboard=true` (last grid config wins the single `dashboard/run.json`),
          then `npm run push -- --remote`.
        - **Plan 2 (phone UI): IN PROGRESS 2026-07-20 — 8.5 of 12 tasks done.** See the
          "Plan 2 execution checkpoint" section below before touching anything.
          Plan: `docs/superpowers/plans/2026-07-20-trade-review-phone-ui.md` (12 TDD tasks).
          `docs/superpowers/specs/2026-07-20-trade-review-phone-ui-design.md` — swipe deck (one
          trade per screen) + Overview tab; Focus/Full zoom toggle instead of pinch; fixed 6-flag
          taxonomy, one flag per trade; **new `marks` table** (`better-entry`/`T1`/`T2`/`T3`/
          `exit-here` pinned to a bar timestamp) giving iteration 9's scale-out work the human
          ground truth it lacks. Supersedes the single-scrolling-page UI sketch in the 07-19 spec.
        - Frontend hosting = Cloudflare Worker with static-assets binding (not legacy Pages); all
          Cloudflare code lives under `dashboard/`.
      - [ ] (4) **3-tier scale-out exit experiment ← NOW THE PRIORITY.** Item 2 proved the tight
        single-target geometry is net-negative, so this is the fix, not a nice-to-have. Extend
        `intrabarExit` to bank ⅓ at each of T1/T2/T3 with a ratcheting stop (defaults T1 0.75 /
        T2 1.5 / T3 3.0 ATR — **confirm exact levels + ratchet rule with user before coding**).
        See design spec §4.
- [x] Execution realism in the backtest: intrabar stop/target triggers modeled (2026-07-04, item 2
      above). Remaining realism gap: fills are still mid-price + a modeled spread rather than true
      per-bar bid/ask; acceptable for now.

---

## Strategy

- [x] Strategy tuning round 1 complete (2026-07-04): 80% win rate held out-of-sample, LONG-only,
      config promoted to `application.yaml`. ⚠️ **Superseded by the 2026-07-04 pnl audit**: the win
      rate is real but the +0.12/+0.45 net was close-fill optimism — under honest intrabar fills the
      profile is net-**negative** (IS −0.14, OOS −0.29 pts/trade). Win rate ✅, money ❌. Next lever is
      the 3-tier scale-out (Observability item 4), not more entry tuning.
- [x] ~~Establish RSI-only baseline~~ superseded by the sweep-harness iterations below
- [x] ~~Fix entry/exit rules — current win rate ~32%~~ fixed: geometry + trend gate (iterations 2–7)
- [x] Implement SHORT entries — confluence runs bullish votes LONG, bearish votes SHORT
- [x] Layer in 5-pillar confluence toward >85% accuracy target (scoring/voting, threshold-tunable):
  - [x] Pillar 1: Technical indicators — RSI extreme + Bollinger Band touch (bull/bear votes)
  - [x] Pillar 2: Candlestick patterns — engulfing / harami / hammer / shooting-star
  - [x] Pillar 3: Support & resistance levels — close near lookback low/high (ATR proximity)
  - [x] Pillar 4: Chart patterns — break-of-structure proxy (break prior lookback high/low)
  - [x] Pillar 5: Volume / trend confirmation — above-average volume aligned with EMA trend

### Confluence tuning — where we left off (2026-06-14)

Current `application.yaml` settings: threshold 2, RSI 25/75, proximity-atr 0.3, swing-lookback 20.
Dataset: 2078 5m bars ≈ 26.6 trading days.

Pillar combination analysis at these settings (168 trades, 32% win rate, ~6.3/day):

| Combo                           | Count | Win% | Notes                                    |
|---------------------------------|-------|------|------------------------------------------|
| Structure + Vol+Trend           |  55   |  29% | **Noise** — both describe trending bars  |
| RSI+BB + S/R                    |  47   |  34% | Clean mean-reversion at extremes         |
| Candle + Vol+Trend              |  24   |  21% | Weak                                     |
| Candle + S/R                    |  22   |  41% | Good — reversal candle at real level     |
| Candle + Structure + Vol+Trend  |   7   |  43% | Better quality when 3 align              |
| Candle + RSI+BB + S/R           |   1   | 100% | Golden combo, but very rare              |

**Key finding:** Structure (close > prior lookback high) and Vol+Trend (close > EMA + high volume)
are correlated — both fire in trending bars. They generate 55 trades at only 29% win rate.
Removing this pair would cut trades from 168 → 113 and lift overall win rate.

**Next action options:**
- Disable `enable-structure: false` → drops the noisy Structure+Vol+Trend pair entirely
- Keep Structure but redesign it as something less correlated with Vol+Trend (e.g. require
  price to have been *below* the level recently before breaking it)
- Goal: ~5 quality trades/day at materially higher win rate than 32%

### Tuning iteration 1 — full-year sweep (2026-07-03)

Harness: `ConfluenceSweepTest` (`-Dtest=ConfluenceSweepTest -Dsweep=true`), grid of 21 configs in one
JVM. **In-sample window: 2024-12-04 → 2026-01-01** (76,796 5m bars, 336 trading days, avg spread
0.48 pts). **Jan–May 2026 is held out for out-of-sample validation — do not tune against it.**
"Net" = after one full spread per round trip. Modeling gaps (noted in harness javadoc): mid-price
fills, stops/targets trigger on bar close not intrabar wicks.

Key rows (full table in the harness output; base config = yaml except where named):

| Config                        | Trades | /day | Win% | netAvgPnl (pts) |
|-------------------------------|--------|------|------|-----------------|
| th2_struct (old baseline)     | 6510   | 19.4 | 37%  | **−0.40**       |
| th2_noStruct_stop3.0_tgt1.0   | 5408   | 16.1 | 71%  | **−0.34**       |
| th3_noStruct (yaml geometry)  | 335    | 1.0  | 40%  | +0.67           |
| th3_noStruct_stop2.0_tgt1.0   | 335    | 1.0  | 66%  | +0.73           |
| **th3_noStruct_stop3.0_tgt1.0** | 334  | 1.0  | **70%** | +0.58        |
| th3_noStruct_stop1.5_tgt1.5   | 337    | 1.0  | 57%  | +1.15 (best expectancy) |
| th4_* (any)                   | 3      | 0.0  | —    | dead            |

**Findings:**
1. The 26-day Dec-2024 window behind the old 32% baseline was unrepresentative — threshold 2 actually
   fires 15–19 trades/day over the year and **loses money after spread at every geometry tested**.
   Threshold 2 is a dead end, full stop.
2. Threshold 3 + structure off ≈ 1 trade/day, profitable after spread at every geometry — this is the
   quality zone. Threshold 4 ≈ 3 trades/year — dead.
3. **Stop/target geometry is the dominant win-rate lever** (as predicted: target > stop structurally
   caps win rate). Same entries went 40% → 70% by moving from stop 1.5/target 3.0 to stop 3.0/target 1.0.
   Win rate and expectancy trade off: 1.5/1.5 gives 57% win but the best netAvgPnl (+1.15).
4. Win rate ≈ net win rate everywhere — trade sizes (ATR-scale) dwarf the 0.48-pt spread; spread
   mainly erodes expectancy, which is what kills threshold 2.

**Gaps vs. north star:** 70% < 80% win rate; 1.0 < ~5 trades/day.

**Next (iteration 2):** anchored on th3_noStruct — (a) push geometry further (stop 3.5–4.0, target
0.5–0.75) watching that netAvgPnl stays clearly positive; (b) raise cadence by loosening pillar gates
(RSI 30/70, proximity-atr 0.4–0.5, shorter swing-lookback) so 3-of-4 votes fire more often; (c) later,
time-of-day/volatility-regime filters as win-rate polish.

### Tuning iteration 2 — 80% crossed in-sample (2026-07-03)

Same window/harness as iteration 1. All configs: threshold 3, structure off, RSI 25/75 unless noted.

| Config                          | Trades | /day | Win% | netAvgPnl (pts) |
|---------------------------------|--------|------|------|-----------------|
| **geo stop4.0/tgt0.5**          | 333    | 1.0  | **81%** | +0.41        |
| geo stop3.5/tgt0.5              | 335    | 1.0  | 80%  | +0.33           |
| geo stop4.0/tgt0.75             | 332    | 1.0  | 79%  | +0.52           |
| geo stop3.0/tgt0.75             | 335    | 1.0  | 76%  | +0.78 (best expectancy) |
| gates prox0.5/look10 (geo 3.0/1.0) | 720 | 2.1  | 72%  | +0.34           |
| gates rsi30-70 or 35-65 (any)   | 555–940 | 1.7–2.8 | 69–71% | **negative to +0.34** |

**Findings:**
1. **The 80% win-rate bar is reachable in-sample**: stop 4.0 ATR / target 0.5 ATR → 81% on 333
   trades, still positive after spread. Win rate climbs smoothly along the geometry axis
   (70→76→79→81%), so this is a real response surface, not a lucky cell.
2. Expectancy thins as win rate climbs (+0.78 at 76% vs +0.41 at 81%). Classic high-win-rate shape:
   wins ~0.5 ATR, losses ~4 ATR — one stop-out eats ~8 wins. **Position sizing / max-drawdown
   controls (Infrastructure backlog) are mandatory before live.**
3. **Cadence lever found**: S/R proximity 0.5 ATR + swing-lookback 10 doubles trades to 2.1/day and
   *raises* win rate to 72% (at geo 3.0/1.0). The S/R pillar was over-strict, not under-strict.
4. **RSI 25/75 is load-bearing**: widening to 30/70 or 35/65 adds trades that lose money after
   spread. Do not loosen pillar 1.

**Next (iteration 3):** compose the two wins — gates (prox 0.4–0.6 × look 8–14) × geometry
(stop 3.0–4.0 × tgt 0.5–0.75), all at th3/noStruct/RSI 25/75. If effects stack: ~2/day at ~80%.
Then pick 2–3 candidates and run the held-out Jan–May 2026 window ONCE as the out-of-sample referee.

### Tuning iteration 3 — compose holds: 84% at 2.4/day in-sample (2026-07-03)

Same window/harness. 45 configs: prox {0.4,0.5,0.6} × look {8,10,14} × geometry
{3.0/0.75, 3.5/0.5, 3.5/0.75, 4.0/0.5, 4.0/0.75}, all th3/noStruct/RSI 25/75.

The cadence and geometry effects stack. The entire look 8–10 × prox 0.4–0.6 × stop4.0/tgt0.5
region sits at **83–84% win** on 620–950 trades — a plateau, not a spike. look 14 degrades toward
zero net expectancy everywhere (stale swing levels). Best rows per profile:

| Profile               | Config                          | Trades | /day | Win% | netAvgPnl |
|-----------------------|---------------------------------|--------|------|------|-----------|
| Win-rate champion     | prox0.5 look8 stop4.0 tgt0.5    | 812    | 2.4  | 84%  | +0.52     |
| Cadence variant       | prox0.6 look8 stop4.0 tgt0.5    | 941    | 2.8  | 84%  | +0.44     |
| Balanced              | prox0.5 look10 stop4.0 tgt0.75  | 710    | 2.1  | 81%  | +0.69     |
| Expectancy champion   | prox0.5 look10 stop3.0 tgt0.75  | 722    | 2.1  | 78%  | +0.86     |

**Out-of-sample validation (next, one shot):** run exactly these candidates on the held-out
2026-01-01 → 2026-05-01 window (~85 trading days, expect ~200 trades each). Pass = win rate holds
within a few points of in-sample and net expectancy stays clearly positive. No retuning against
this window — if candidates crater, back to in-sample with walk-forward splits instead.

### Out-of-sample validation — FAILED on expectancy (2026-07-04)

Window 2026-01-01 → 2026-05-02: 24,126 5m bars, 104 trading days, avg spread 0.55 pts (vs 0.48 IS).

| Config                          | IS win% / net | OOS win% / net |
|---------------------------------|---------------|----------------|
| prox0.5 look8 stop4.0 tgt0.5    | 84% / +0.52   | 79% / **−1.68** |
| prox0.6 look8 stop4.0 tgt0.5    | 84% / +0.44   | 79% / **−1.64** |
| prox0.5 look10 stop4.0 tgt0.75  | 81% / +0.69   | 78% / **−1.29** |
| prox0.5 look10 stop3.0 tgt0.75  | 78% / +0.86   | 75% / **−0.79** |

**Read:** win rate survived (−3 to −5 pts, normal shrinkage — the entry logic generalizes), but
**net expectancy flipped hard negative**. Jan–May 2026 is a different volatility regime (higher
spread, cadence 2.4→3.0/day), and the wide-stop/tight-target geometry pays for its high win rate
with catastrophic-sized rare losses — in a hotter regime those swamp the small wins. Also the
close-triggered stop understates real losses most in fast markets (close can land far beyond the
stop level), so live would be worse still.

**Decisions:**
1. The 2026 window is now *burned* for candidate selection (we looked at it). Future config
   selection must come from walk-forward splits inside Dec'24–Dec'25; 2026 gets one final look
   only for the single end-stage config.
2. Win rate alone is officially a misleading objective for this strategy shape — every future
   iteration gates on **net expectancy ≥ 0 in every regime/quarter**, then maximizes win rate.

**Next (iteration 4):** find where the losses live. `TradeResult` already carries a per-trade
`VolatilityRegime` (LOW/NORMAL/HIGH, rolling ATR percentile — no look-ahead). Extend the harness to
break down win%/net expectancy per regime and per quarter, run on IS. If losses concentrate in the
HIGH regime, add a regime gate (skip entries when ATR is in the top rolling percentile band) — an
explainable filter consistent with the 5-pillar reproducibility ethos — then re-check stability
across 2025 quarters via walk-forward before ever touching 2026 again.

### Tuning iteration 4 — regime hypothesis dead; quarter instability is the disease (2026-07-04)

Per-regime / per-quarter breakdown of the four candidates on IS:

1. **Regime gate is the wrong fix**: HIGH-vol is the *best* regime (+0.96 to +1.18 net across
   candidates); LOW-vol is the drag (−0.29 to +0.23). A skip-high-vol filter would hurt. Hypothesis
   falsified before writing any filter code — that's the breakdown doing its job.
2. **The real disease: expectancy whipsaws by quarter even in-sample.** E.g. win-rate champion:
   2024Q4 −1.86, 2025Q1 −0.39, Q2 +2.19, Q3 −0.41, Q4 +1.42. The aggregate +0.52 was two great
   quarters subsidizing three negative ones. OOS didn't reveal a regime shift; it revealed that
   "profitable on average" was never "profitable consistently".
3. Win rate is stable everywhere (77–89% per quarter) — the entries are fine. With wins ~0.5 ATR vs
   losses ~4 ATR, the quarter's sign hinges on 2–3 extra stop-outs — the geometry is knife-edge.
4. Most robust candidate: **stop3.0/tgt0.75** (all regime rows positive, only 2024Q4 clearly
   negative, least-bad OOS). The extreme 4.0/0.5 geometry is the fragile one.

**Next (iteration 5): time-based exit.** With target 0.5–0.75 ATR vs stop 3–4 ATR there's a hole in
the exit logic: trades that hit neither level drift for hours carrying full stop-size tail risk.
Add a `max-holding-bars` knob (exit after N bars if neither stop nor target hit — ta4j
`OpenedPositionMinimumBarCountRule` OR'd into the exit), sweep N × the two sane geometries, and
gate candidates on **every quarter net ≥ ~0**, not the aggregate.

### Tuning iteration 5 — time stop falsified (2026-07-04)

`max-holding-bars` knob implemented (kept — useful risk control), but it's not the stabilizer:
hold 24–48 bars ≈ no change vs hold 0; hold 6–12 cuts win rate to 63–77% while the bad quarters
stay negative (hold12 on 4.0/0.5: Q4'24 −1.35, Q1'25 −0.10, Q3'25 −0.02). **The bad quarters lose
via genuine stop-outs, not drifting trades.** Quarter pattern is consistent across all geometries:
Q4'24 always worst (−1.4 to −2.7 — note: Dec 2024 is the window the original 32% baseline came
from), Q1'25/Q3'25 mildly negative, Q2'25/Q4'25 strongly positive.

**Next (iteration 6): trend-direction diagnosis.** All entries are mean-reversion (RSI extreme +
BB touch + near S/R); clustered stop-outs are the signature of fighting a strong trend. Add a
LONG/SHORT × quarter breakdown to the harness — if bad quarters bleed on one side only, the fix is
a hard higher-timeframe trend gate (only long when trend up/flat, only short when down/flat),
which is explainable and pillar-consistent. No strategy code until the data confirms.

### Tuning iteration 6 — directional signature confirmed (2026-07-04)

LONG/SHORT × quarter split on the robust profile (th3/noStruct/prox0.5/look10/stop3.0/tgt0.75):

| Quarter | LONG net | SHORT net |
|---------|----------|-----------|
| 2024Q4  | **−2.58** | −0.57    |
| 2025Q1  | +1.08    | −0.45     |
| 2025Q2  | +0.94    | **+4.98** |
| 2025Q3  | +0.49    | −1.26     |
| 2025Q4  | +1.37    | +1.20     |

1. **Longs are consistently profitable in every 2025 quarter**; the only long disaster is Dec 2024 —
   a selloff month where mean-reversion longs caught falling knives all the way down.
2. **Shorts are a lottery ticket**: bleed in 3 of 5 quarters, jackpot (+4.98/trade) in the Q2'25
   selloff. Classic S&P profile — shorting mean-reversion fights upward drift except in corrections.
3. Both facts point at one fix: **hard higher-timeframe trend gate per direction** — longs only when
   price is above a long EMA (kills Dec-24-style knife-catching), shorts only when below it (keeps
   the crash jackpot, cuts the drift bleed).

**Next (iteration 7):** implement `trend-ema-period` (hard directional gate, 0 = disabled), sweep
{100, 200, 400} × the two lead profiles. Pass bar unchanged: every quarter net ≥ ~0 in-sample, then
walk-forward check, and only then the final one-shot on 2026.

### Tuning iteration 7 — trend gate works; shorts unmasked as a crash lottery (2026-07-04)

Trend gate {0,100,200,400} × two profiles. EMA 200 on stop3.0/tgt0.75 fixes Dec'24 exactly as
designed (−1.90 → +0.30) and Q1'25; EMA 400 over-filters (net negative aggregate). But the
per-direction view is the real finding:

- **LONGS (3.0/0.75 + trend200): 4/5 quarters positive** (+0.39, +0.81, −0.38, +0.61, +0.88),
  ~80% win, ~0.9/day, net +0.46/trade. The residual Q2'25 −0.38 is the gate blocking recovery
  dip-buys — a far smaller leak than the Dec'24 one it closed.
- **SHORTS: the entire profit is one quarter** (+5.54/trade in the Q2'25 selloff); −1.3 to −3.2
  everywhere else, gated or ungated. On an upward-drifting index this is not an edge — it's crash
  insurance that occasionally pays. Cut them from the base profile.

**Final candidate (pre-committed before the 2026 one-shot):** LONG-only, th3, structure off,
prox 0.5, look 10, stop 3.0, tgt 0.75, trend-EMA 200, RSI 25/75. Expected IS: ~80% win, ~0.9/day,
net ≈ +0.46/trade, worst quarter −0.38.

**Next (iteration 8):** add `enable-long`/`enable-short` knobs; confirm candidate in-sample with a
monthly breakdown; then ONE shot on 2026 with exactly this config — no retuning on the result. If
it holds (net ≥ 0, win ≥ ~75%): promote to `application.yaml` as the US500 profile and surface the
cadence question (0.9/day vs ~5 target) to the user. If it fails: stop and reassess with the user.

### Iteration 8 — FINAL: out-of-sample PASS, config promoted (2026-07-04)

**Config (now in `application.yaml`):** LONG-only, threshold 3, structure off, prox 0.5, look 10,
stop 3.0 ATR, target 0.75 ATR, trend-EMA 200, RSI 25/75.

| Window                        | Trades | /day | Win% | netAvgPnl |
|-------------------------------|--------|------|------|-----------|
| In-sample (Dec'24 → Dec'25)   | 309    | 0.9  | 79%  | +0.45     |
| **Out-of-sample (Jan–May'26)**| 101    | 1.0  | **80%** | **+0.12** |

Win rate held with zero overfit gap; net expectancy stayed positive where the pre-gate candidates
lost −0.79 to −1.68 on the same window. Monthly variance is high (IS worst −3.32, best +5.77;
OOS Feb +2.46 carried Jan/Mar slight negatives) — the edge is real but thin.

Win rate held with zero overfit gap. ⚠️ **The net expectancy here was measured with the old
close-based exit model and does not survive the pnl audit — see the re-validation below.**

### Iteration 9 — pnl audit re-validation: expectancy FALSIFIED (2026-07-04)

Item 2 of the observability round rebuilt exits as an honest intrabar bracket (fill at the
stop/target level, conservative stop-wins tie-break; see design spec §2 and
`docs/dev-environment.md`). Re-ran the **exact same promoted config** on both windows:

| Window                        | Trades | /day | Win% | netAvgPnl (old → new) | $net/day @ $1/pt |
|-------------------------------|--------|------|------|------------------------|-------------------|
| In-sample (Dec'24 → Dec'25)   | 309    | 0.9  | 82%  | +0.45 → **−0.14**      | −$0.13            |
| **Out-of-sample (Jan–May'26)**| 101    | 1.0  | 79%  | +0.12 → **−0.29**      | −$0.28            |

**Win rate is real and holds (~80%); the profit was not.** The 0.75:3.0 target:stop geometry loses
more on the ~20% full-stop losers than the tight target makes on the ~80% winners → net negative
once stops actually fill intrabar at the level. The +0.12/+0.45 was close-fill optimism.

**Current best result: none makes money under honest fills.** Honest scorecard vs. north star:
- Win rate 80%+: ✅ hit, in- and out-of-sample.
- Reproducible/explainable: ✅ 3-of-4 pillar votes + trend gate + geometry, all in yaml.
- ~5 trades/day: ❌ ~1/day (cadence comes from adding instruments, not looser filters).
- Makes money: ❌ **net negative** under intrabar fills. The entry edge (80% win) is genuine; the
  exit geometry throws it away. Fixing *exits*, not entries, is the path forward.

### US500 personality — honest-pnl feature slices (2026-07-04)

Regenerated `experiments.sqlite` under the intrabar model, then `query.py slice`d the promoted
profile (#4, baseline 82% win / **−0.14** net). Conditional expectancy per entry-feature bucket
(the signal, not outcomes) — this is what "US500 personality" means in practice:

| Feature | Best bucket | Worst bucket | Read |
|---|---|---|---|
| **dist_to_trend_ema_atr** | 0–1.85 ATR: **90% / +1.30** | 3.7–5.8 ATR: 72% / −1.21 | **Strongest, monotonic.** Buy the dip *near* the EMA; chasing extension bleeds. |
| atr_percentile (regime) | 0.52–0.74: 84% / +1.01 | 0.74–1.0: 77% / −1.41 | Mid-vol is the sweet spot; dead-calm and high-vol both drag. |
| rsi_value | 31–40: 87% / +0.55 | 9–31 (deep oversold): 74% / −1.31 | Counterintuitive — deepest oversold = knife-catch, not the best dip. |
| volume_ratio | ~1.0: 80% / +0.55 | >3.2× (spike): 25% / −5.97* | Avoid panic-volume bars. *only 4 trades. |
| hour_utc | 18–23 UTC: 85% / +0.17 | 13–18 UTC: 77% / −0.38 | Weak. |
| day_of_week | Tue: 87% / +0.74 | Wed: 85% / −0.83 | Likely noise (base-rate trap). |

**In-sample headline was: the edge lives within ~2 ATR of the trend EMA** (near-EMA bucket 90% win /
+1.30 vs −0.14 baseline). This motivated iteration 10 — **but it did NOT survive out-of-sample.**

### Iteration 10 — dist-to-trend-EMA ceiling filter: FALSIFIED out-of-sample (2026-07-04)

Wired the personality lead as a real knob (`backtest.strategy.trend-ema-max-atr`: only enter within
N ATR of the trend EMA; `StrategyFactory` gate + config). Swept N on top of the promoted profile
in-sample, pre-committed to **2.0 ATR** (best expectancy, matched the ~2 ATR read), took ONE
out-of-sample shot — no retuning:

| Config (2.0 ATR ceiling) | Window | Trades | Win% | netAvgPnl |
|---|---|---|---|---|
| emaCeil_2.0atr | In-sample | 73 | 89% | **+1.43** |
| emaCeil_2.0atr | **Out-of-sample** | 26 | 77% | **−2.02** |

Every ceiling level (1.0–3.0 ATR) was net-**negative** OOS and worse than the unfiltered −0.29. The
in-sample lift was small-sample overfit (73→26 trades), the exact base-rate trap the design spec
warns about. **Conclusion: the near-EMA "edge" is not a real edge.** The knob stays in code
(disabled, `trend-ema-max-atr: 0`) as a documented dead-end; do not retune it against the test set.
The lesson reinforces the audit: entry filtering isn't the lever — **exit geometry is.**

**New direction (2026-07-19): interactive visual review + human-in-the-loop.** Ground rules are now
locked in `docs/instrument-personality-playbook.md` — read it before strategy work. In short: build
the interactive trade-review tool (design spec §3, now a first-class deliverable) as the
hypothesis-generation engine; use our eyes to *propose* refinements and the full dataset with OOS
discipline to *dispose* of them (eyes propose, data disposes; a flagged trade is a hypothesis, not a
label; every hint earns its place out-of-sample with zero privilege). This is a repeatable
per-instrument "personality" exercise — lock the process, re-derive each instrument's numbers. Work
spec-first (SDD). Roles: Opus directs + thinks until it calls in Fable for the hardest reasoning;
Sonnet codes to spec; Haiku does boilerplate.

**Where a fresh session should pick up:**
1. **Interactive visual review tool (design spec §3 + playbook) — build it first.** It shows the
   capped-winner problem with your own eyes and is the engine for the hypothesis loop. KPI tiles,
   trade gallery (50 bars each side, stop/target lines), conditional slices, "flag as hypothesis"
   capture. Self-contained Claude Artifact.
2. **3-tier scale-out (design spec §4, exit geometry) — the primary money lever.** The entry edge
   (80% win) is genuine but every entry-side filter tried has failed to add net edge; the 0.75:3.0
   target:stop geometry is what makes it net-negative. Bank ⅓ at T1/T2/T3 with a ratcheting stop.
   **Confirm exact levels + ratchet rule with the user**, then extend `intrabarExit` (it already
   walks bars checking levels — bank a third at each tier instead of returning on first touch).
3. Cadence via breadth: second instrument, run through the same playbook loop, with its own profile
   (per-instrument config design).
4. Risk controls before any live use (position sizing, max drawdown, circuit breaker).
5. MONITOR-mode validation remains open (needs Capital.com network access + credentials).

---

## Infrastructure

- [x] Retargeted the project to JDK 21 (2026-07-04) to match the web-session container runtime, so
      `./mvnw test` builds with no flag. Was Java 26; no 22–26-only feature was in use.
- [ ] Implement TRADE mode order execution (blocked until strategy accuracy is proven)
- [ ] Add risk controls before any live trading (position sizing, max drawdown, circuit breaker)
- [ ] Design per-instrument config profiles ("personality") — today `backtest.strategy` is one flat
      block hardcoded to `US500`; each new instrument needs its own tuned thresholds/pillar weights
      rather than inheriting US500's settings

---

## Completed

- [x] Phase 1–4: Backtest engine, Capital.com REST + WebSocket integration, historical price storage
- [x] TA4j strategy pipeline (BarSeriesFactory → IndicatorBundle → StrategyFactory → BacktestRunner)
- [x] JFreeChart visualisation of backtest results
- [x] SQLite persistence with Flyway migrations

---

## Plan 2 execution checkpoint (2026-07-20)

Read this first if you are resuming Plan 2 (the phone UI) in a fresh session.

**Branch:** `claude/cloudflare-dashboard-plan` (all work pushed).
**Plan:** `docs/superpowers/plans/2026-07-20-trade-review-phone-ui.md`
**Spec:** `docs/superpowers/specs/2026-07-20-trade-review-phone-ui-design.md`
**Method:** superpowers subagent-driven-development. Per-task review is mandatory — see
`.claude/CLAUDE.md`. Local ledger at `.superpowers/sdd/progress.md` (gitignored; git log is the
authority if it is missing).

### Done and reviewed clean

| # | Task | Commit |
|---|------|--------|
| 1 | shared flag/mark vocabulary + server-side flag validation | `44357ac` |
| 2 | `marks` table migration (applied local **and** remote D1) | `abb2206` |
| 3 | `/api/marks` place / move / delete | `3266a13` |
| 4 | pull marks alongside feedback to `experiments/marks.json` | `4082c5d` |
| 5 | Vite/React scaffold + jsdom vitest project, builds into `dashboard/public/` | `7d23ac9` |
| 6 | typed API client (+ fixes: URL encoding, missing columns, boundary validation) | `89f2a12`, `d7ca433`, `6f99583` |
| 7 | chart geometry (pure functions) | `df8f164` |
| 8 | TradeCard — SVG candles, stop/target, markers, Focus/Full | `49d6df3` |

### Task 9 (`useAnnotations`) — ✅ COMPLETE, approved at `9957345` (fable review, 2026-07-21)

Approved after 4 implementation rounds and 4 reviews. Final state: **api 21/21, ui 51/51, typecheck
clean**, verified by the controller independently at each step. Three accepted limitations are
documented below — they are deliberate, ruled on by the human, and pinned by regression tests.

> **⚠️ THIS FILE IS CLOSED FOR FURTHER EXTENSION ON ITS CURRENT ARCHITECTURE.**
> The approving fable reviewer's explicit recommendation, and the single most important thing to
> carry forward from this task. The hook is *correct* as specced (flags + marks), but its
> correctness rests on several global, comment-only invariants the type system does not enforce —
> ref mirrors state synchronously; empty-array-means-absent-key; `apply`/`restore` must stay pure;
> ownership is decided by an issue-time sequence number, not by value; array-valued slots must be
> merged per sub-key, never wholesale. Evidence it has crossed from "reviewable" to "needs
> fable-tier verification every time": two sonnet-tier reviews returned clean while real bugs were
> live, and the final review needed the merge logic extracted into a standalone script and six
> interleavings hand-simulated before it could be trusted.
>
> **If a third optimistic write type, bulk operations, or any change to `Mark`'s shape comes up,
> do NOT patch this machine again.** Redesign onto a server-confirmed baseline (TanStack Query's
> optimistic-mutation model, per-slot write serialisation, or confirm-on-response). Each of the last
> two fixes required inventing new machinery — sequence numbers for ABA, then a parallel merge
> function for per-kind reconciliation — rather than a local change. That is a design accumulating
> moving parts under stress, not converging.

Non-blocking follow-up noted by the reviewer: `mergeMarksKeepingOverrides` (`useAnnotations.ts:47-50`)
silently depends on the DB's `UNIQUE(signal_key, kind)` constraint; two same-kind loaded rows would
both pass through, worst case a duplicate React key warning. The dependency pre-dates this diff and
is not widened by it. Worth a one-line comment; added to the deferred-Minor list.

### Historical record of the Task 9 rounds (kept for the branch review)

Commits: `90630af` (initial), `f2a7c51` (fix round 1), `c05ae39` (fix round 2 — latest).
This is the optimistic flag/mark state hook — every write in the app flows through it, which is why
it has taken three rounds. Two review rounds found bugs no passing test could catch:

1. The plan's own reference code decided POST-vs-DELETE by reading a variable assigned inside a
   `setState` updater on the very next line — so `deleteMark` was never called. Removing a mark
   would have vanished from the UI and silently persisted in D1.
2. The first fix read `marks` from the callback closure and wrote a precomputed array over `prev`,
   so two taps in one tick dropped one mark locally though the server took both.
3. The revert restored a captured snapshot unconditionally, so a failed older write could wipe a
   newer successful one.
4. Round 2 found the value-equality "is this still mine" check cannot handle ABA (A → B → A), and
   that the initial load still did the wholesale non-functional overwrite finding 2 was about.

Round-2 fixes (`c05ae39`) replaced the value-equality ownership test with a per-key monotonic
sequence gate and made the initial load merge functionally from `prev`. Suite at this commit:
**api 21/21, ui 47/47, typecheck clean** (verified by the controller, not just claimed).

Two residual edge cases were left deliberately unfixed and are documented in the fix report:
1. Two concurrent writes to the same slot that BOTH fail with out-of-order settlement can still
   leave state holding a value the server has neither of. A real fix needs a
   "last-confirmed-by-server" baseline, not just a sequence gate.
2. A mark removed locally while a load is in flight can resurface if that load's GT-stale GET still
   carried the pre-removal row. Needs a "locally touched" concept beyond "re-apply what is in prev".
Both are narrower than what was fixed, and both diverge local state only — nothing wrong is
persisted, and a reload reconciles.

**NEXT ACTION: re-review `c05ae39` (base `49d6df3`) before starting Task 10.** Use an Opus-tier
reviewer — the sonnet-tier reviews passed this task twice while real bugs were live. Judge the two
residual edge cases explicitly: accept as documented limitations, or fix. Do not mark Task 9
complete until that review is clean.

**Session 2026-07-21 progress:**
- Re-verified `c05ae39` independently (not just trusting the prior checkpoint): `npm test` →
  **api 21/21, ui 47/47**; `npm run typecheck` → **clean** on both projects. Claims hold.
- Re-review dispatched to a **fable-worker** (not sonnet — this is ABA / out-of-order-settlement /
  ref-vs-committed-state race logic, and the sonnet tier has already passed this file twice with
  live bugs in it). Asked for an explicit ruling on both residual edge cases and for the
  "diverges local state only, nothing wrong is persisted" claim to be *verified*, not assumed.
### Fable re-review of `c05ae39` — verdict **REQUEST CHANGES** (2026-07-21)

The escalation was justified: the fable reviewer found **two bugs that both sonnet reviews and the
controller's own read missed**. The core per-slot sequence gate itself was traced and found *sound*
under every interleaving (two taps/tick, ABA, remove-then-place, StrictMode double-invoke, and the
ref-vs-committed-state ordering) — the defects are around it, not in it. Both findings independently
re-confirmed against the source by the controller before acting.

**Finding 1 (new, most likely to bite in real use) — the initial-load merge drops server-confirmed
marks of an unrelated kind.** `mergeKeepingOverrides` (`useAnnotations.ts:29-33`) resolves
loaded-vs-local at `signal_key` granularity, so a local write replaces the whole `Mark[]` wholesale
— even though every *other* path in the file scopes strictly to one `kind` (that is the entire point
of `slot: signalKey::kind`, line 216-218). Failure needs no concurrency and no error: trade already
has `[T1@bar1, T2@bar5]` server-side; trader taps a new `T1` before the initial `getMarks()`
resolves; the merge takes the local array wholesale and **`T2` vanishes from the UI** though it was
never touched and still exists in D1. This is the same root cause as disclosed edge case (b) but
strictly wider than (b)'s "a removal resurfaces" framing — it *drops* good data on an ordinary add.
Untested: the two "written before initial load resolves" tests (`useAnnotations.test.tsx:270-336`)
both resolve the load with `[]`, so they cannot reach it.

**Finding 2 (new) — `setError`/`clearError` are not gated by the ownership check that protects the
revert** (`useAnnotations.ts:98-105`). (2a) A superseded write's late failure calls `setError`
unconditionally, so an error banner appears though the current value is correct and nothing is
wrong — the existing test at line 110-137 drives exactly this and would fail today if it asserted
on `error`. (2b) `clearError` is a global toggle with no slot affinity: any unrelated success
anywhere erases a genuine unresolved error for a different trade, so a trader never learns their tap
didn't stick. **2b is plan-shape** (`error: string | null` singular, plan line 1372) → human call.

**Rulings on the two previously-disclosed residual edge cases** (both traced, and the
"local-state-only, nothing wrong persisted, reload reconciles" claim **verified, not assumed**):
- **(a) two concurrent same-slot failures settling out of order → accept as documented limitation.**
  Real (`restore` returns an issue-time capture, not a server-confirmed baseline), but neither POST
  ever reached D1 and a fresh mount reconciles. A proper fix needs a last-confirmed-by-server
  baseline — disproportionate for a double-failure-on-one-slot case.
- **(b) removal resurfacing from a stale in-flight load → accept, but RE-SCOPE.** The honest
  statement of the defect is Finding 1's ("the load merge treats a signal_key's mark array as atomic
  instead of reconciling per `(signal_key, kind)`"), which is wider than what this bullet claimed.
  Do not let the narrow framing above stand as the accepted disclosure.

**Plan-mandated finding (flagged, NOT silently fixed, per `.claude/CLAUDE.md`):** the plan's own Step
3 reference code (plan lines 1518-1525) still contains verbatim the original bug #1 — `removing`/
`previous` assigned inside a `setMarks` updater and read on the next line, so `deleteMark` never
fires. The shipped code correctly deviates. **The plan doc itself needs annotating** so a cold
re-read doesn't reintroduce it. Documentation follow-up, not a blocker on this commit.

**Test gaps to close:** (i) assert `error` after a superseded write's late failure; (ii) initial-load
merge where the load returns *other kinds* for a key with a concurrent local write; (iii) no test
renders under `<StrictMode>` despite the code's comments (lines 69-72, 132-133) leaning hard on
double-invoke safety; (iv) no regression test pins the *accepted* behaviour of (a)/(b).

### Human rulings taken 2026-07-21

- **Finding 1 → FIXED in `9957345`.** Reconcile the initial-load marks merge per
  `(signal_key, kind)` instead of per `signal_key`, via a new `mergeMarksKeepingOverrides`; flags
  keep the original helper. Regression test confirmed failing first. Diff read and suite re-run by
  the controller, not just taken from the worker's report: **api 21/21, ui 51/51, typecheck clean.**
  Four tests added (regression, StrictMode, and pins for limitations (a) and (b)).
  **It does not narrow limitation (b) — see (b) below.**
- **Finding 2b → ACCEPT as a documented limitation.** The plan's singular `error: string | null`
  stays; Task 10's interface is unaffected. Recorded as limitation **(c)** below.
- Plan doc annotated (this session) with a ⚠️ block above the buggy `toggleMark` reference code so a
  cold re-read cannot reintroduce the never-calls-`deleteMark` bug. Plan-mandated finding closed.

### Accepted limitations of `useAnnotations` (deliberate, not oversights)

- **(a)** Two concurrent writes to the same slot that BOTH fail with out-of-order settlement can
  leave state holding a value the server has neither of. `restore` returns an issue-time capture,
  not a server-confirmed baseline. Local-state-only; nothing wrong is persisted; reload reconciles.
- **(b)** A mark removed locally while a load is in flight can resurface if that load's stale GET
  still carried the pre-removal row. **The Finding 1 fix (`9957345`) does NOT narrow this — it makes
  it manifest more consistently.** Before the fix, a mixed-kind case (one kind removed, another still
  present locally) *accidentally masked* the resurfacing, because the wholesale key overwrite threw
  the entire loaded row set away. Per-kind merging cannot distinguish "this kind was never touched
  locally" from "this kind was touched and then removed to nothing" — both read as *absent from the
  local array* — so a stale loaded row for a removed kind now wins consistently. Pinned by test.
  A real fix needs an explicit "locally touched" / tombstone concept, which is exactly the kind of
  extra machinery the stopping rule below says not to bolt on in another patch round.
- **(c)** `error` is a single global string with no slot affinity: a success on ANY slot clears the
  banner belonging to a different, still-failed write, and a superseded write's late failure can
  raise a banner although the current value is correct. A trader can therefore miss that one tap
  didn't stick. Accepted 2026-07-21 as an acceptable tradeoff for a single-toast phone tool.

**NEXT ACTION: Finding 1 fix + tests land → re-review (fable tier again — the sonnet tier has now
been wrong on this file three times) → only then Task 9 complete and Task 10 starts.** Task 9 is
still NOT complete.

### STOPPING RULE for Task 9 — RESOLVED, not triggered

The next review came back **APPROVE**, so the stopping rule's "clean → proceed" branch applied and
Task 9 shipped. The rule is retained below because its *redesign* branch is still the standing
policy for any future change to this file (see the closed-for-extension note above).

Original text:

### STOPPING RULE for Task 9 (set by the human 2026-07-21)

Task 9 has now consumed **four implementation rounds and three reviews**, and each review round has
found real bugs that the previous round's tests passed straight through. That is the signature of a
design that is too hard to get right by patching, not of unlucky implementations. Do not keep
grinding it round after round.

**The next fable re-review is the decision point:**

- **Clean →** mark Task 9 complete, proceed to Task 10. Done.
- **More substantive findings →** STOP patching. Do not dispatch a fifth fix round. Escalate to
  **fable to redesign the approach**, not to fix another symptom. If that does not produce a design
  fable is confident in, **park Task 9 and brainstorm it as its own exercise**
  (`superpowers:brainstorming`) rather than blocking the rest of Plan 2 on it.

**Redesign directions worth putting in front of fable when that happens** (the recurring root cause
is a hand-rolled per-slot optimistic state machine — refs mirroring state, sequence gates, issue-time
restore captures — which is genuinely hard concurrent code we keep re-deriving by hand):
1. **Adopt a library that already solves this** — TanStack Query's mutation + optimistic-update model
   (`onMutate`/`onError` rollback with a server-confirmed cache as the baseline) directly addresses
   accepted limitation (a), which is precisely the "no last-confirmed-by-server baseline" gap.
2. **Serialise writes per slot** — a small per-slot promise queue removes every out-of-order
   settlement case by construction, at the cost of a little latency no phone user would notice.
3. **Reduce the optimism** — confirm-on-response with a pending indicator. Loses some instant-tap
   feel; deletes the entire class of divergence bugs.

Note Task 10 consumes this hook only as props (`flags`, `marks`, `onFlag`, `onToggleMark`), so
**Task 10 is not truly blocked by Task 9's internals** — if Task 9 gets parked, Task 10 can proceed
against that stable interface.

### Remaining

**Task 10 (TradeDeck) implemented at `5d82139` — per-task review IN FLIGHT (opus tier).**
Suite at that commit: **api 21/21, ui 59/59 (51 baseline + 8), typecheck clean**, verified by the
controller. If no review verdict is recorded below, it did not finish — **re-dispatch it**; do not
start Task 11 assuming it passed.

Three deviations from the plan's reference code, all bugs rather than design changes:
1. The plan's prefetch effect had `detail` as a dependency AND wrote to it — every arrival re-ran
   the effect, and its cleanup discarded the other in-flight responses. Quadratic refetch burst
   that threw away good responses. Replaced with a request-once ref, deps `[list, index]`.
2. **Caught by the controller, not the implementer:** the request-once ref and a `live` cleanup
   guard are *mutually destructive*. Advancing the index before a fetch resolved ran the cleanup,
   discarding the responses while the ref suppressed any refetch — stranding the deck on
   "Loading chart…" **permanently**, in the ordinary case of swiping on before the chart loads.
   Proved with a failing test first, then fixed by dropping the guard (`detail` is an id-keyed
   cache, so a late response files under its own id and cannot be misattributed). Failed fetches
   now release the id so a retry is possible. Pinned by a regression test.
3. The plan rendered "No trades for this filter." during the *initial* load. Added a loading state;
   a failed `getTrades` now falls through to empty rather than spinning forever.

Also: two plan-provided tests were racy (`getByTestId` immediately after `waitFor`, assuming the
chart rendered in the same tick as the list) → `findByTestId`. Deviation 2's fix exposed them.

**Lesson worth carrying:** deviation 2 was introduced *by the fix for* deviation 1 and was not
caught by the implementer's own new test. The per-task review is not the only guard — the
controller reading the actual diff is load-bearing too.

### Task 10 review verdict: **APPROVE WITH FOLLOW-UPS** (opus tier, 2026-07-21)

Spec compliance PASS (interface is byte-identical to Task 12's call site, plan 2194-2200 — Task 12
will wire cleanly). Code quality PASS with follow-ups. Reviewer verified empirically in a detached
worktree, and **corrected three controller claims** — recorded here because the corrections matter
more than the praise:
- The "fetch exactly once" test (`TradeDeck.test.tsx:118-131`) **does NOT fail against the plan's
  buggy code** — 8/8 pass. Both mocks settle in one microtask drain so React 19 batches the
  `setDetail` calls, the effect re-runs once, and both ids are already cached. The bug is real
  (reproduced with a 3-trade list and staggered 5ms/60ms mocks → `["t-a","t-b","t-b"]`), but this
  test does not guard it and its name overclaims. **Fix: make the mock gate-controlled.**
- "Quadratic refetch burst" overstated — with a 3-element prefetch window it is a bounded handful.
- The two `getByTestId` → `findByTestId` changes were **not** fixing a live race; both still pass
  when reverted. `findBy` hardens (fails loudly if the chart never renders) but masked nothing.

Confirmed clean by the reviewer: dropping the prefetch cleanup **is** safe (react 19.2.7, so
setState-after-unmount is a no-op; and `trades.id` is a global `TEXT PRIMARY KEY` per
`migrations/0001_init.sql:26`, not per-run, so an id-keyed cache cannot misattribute across runId or
filter changes); the `requested` ref can never strand a trade; `detail` growth is a non-issue
(~660 KB per 102-trade run); `list[index]` cannot go out of bounds (batched `setList`/`setIndex`).
The "advance before resolve" test is the strongest in the file — reviewer re-introduced the `live`
guard and confirmed it fails.

**Open follow-ups — F1/F2/F4/F5 are PLAN-MANDATED and need a human ruling:**
- **F1 swipe fires on vertical scroll** (`TradeDeck.tsx:98-104`). No dx-vs-dy comparison. Scrolling
  down to reach the flag chips with a thumb arc advances the deck — losing your place with an armed
  mark kind now pointing at a *different* trade. Swipe is the design spec's primary navigation.
  Fix: track `touchStartY`, require `Math.abs(dx) > Math.abs(dy)`.
- **F2 two-finger gesture phantom-swipes** (`TradeDeck.tsx:97`). `e.touches[0]` is the *first* finger
  on the surface, not the new one, so a pinch yields `dx = x₂ − x₁` → unintended advance. Sharpened
  by the spec explicitly rejecting pinch-zoom: users *will* try to pinch and instead change trade.
  Fix: `if (e.touches.length !== 1) return;`. (Reviewer confirmed no throw risk, and `touchcancel`
  leaves no phantom.)
- **F4 read failures are silent** (`TradeDeck.tsx:79-83`). A failed detail fetch sits on
  "Loading chart…" indefinitely. It *does* self-heal on the next swipe (the `[i, i+1, i-1]` window
  refetches it at `i-1`), but the user gets no signal that anything failed or that swiping helps.
  No error surface exists anywhere for deck *reads* — the plan's App only surfaces `useAnnotations`
  write errors. Plan-level omission.
- **F5 `getTrades` failure is indistinguishable from an empty result** (`TradeDeck.tsx:42-45`, `:88`).
  Access session expires → "No trades for this filter." → you conclude the losers filter is empty
  and stop reviewing a run with 40 losers in it. Deviation 3 was still an improvement (the plan had
  no `.catch` at all → unhandled rejection *and* the same stuck state).
- **F6 no test exercises the swipe path at all** — 8 tests, zero touch events, and F1/F2 both live
  there. Inherited gap (the plan's test list omitted it too).
- **F7 a11y:** `{index + 1} of {list.length}` is a plain `<span>` with no `aria-live`, so after a
  swipe (no focus change) a screen-reader user is never told which trade they are on; the
  loading↔chart swap has no `aria-busy`. → deferred-Minor list, same bucket as the tabs finding.
- **F3 (not plan-mandated):** fix the overclaiming test — gate-control the mock so it genuinely
  fails against the plan's code.

**Also noted:** an armed mark `kind` persists across trades (as does `zoom`). Defensible and matches
the spec's "three taps, no menus" rhythm — but combined with F1 it is exactly how a stray mark lands
on the wrong trade.

### Human rulings on the Task 10 follow-ups (2026-07-21) — fixes IN FLIGHT

- **F1 + F2 → FIX BOTH, plus the missing swipe tests (F6).** Direction check
  (`|dx| > |dy|`) and a single-touch guard on `touchstart`. Swipe is the tool's primary navigation
  and had zero test coverage; adding horizontal-advances / vertical-does-not / two-finger-does-not /
  below-threshold-does-not.
- **F4 + F5 → distinct failed states, local state only.** Failed list read renders
  `Couldn't load trades.` + Retry; a genuinely empty result keeps the exact existing text
  `No trades for this filter.`; a failed chart read renders `Couldn't load chart.` + Retry instead
  of an indefinite `Loading chart…`. **No new props** — `TradeDeck`'s interface is unchanged, so
  Task 12's call site is unaffected.
- **F3 → fix the overclaiming test.** Rewrite with staggered/gate-controlled mocks and *prove* it
  goes RED against the plan's buggy deps before trusting it.
- **F7 → deferred** to the Minor list for the final whole-branch review.

**All landed in `5c1fc39`.** Suite: **api 21/21, ui 65/65** (59 + 6 net new), typecheck clean —
verified by the controller. F3's rewritten test was proven RED against the plan's buggy deps
(2 calls for `t-b`) before being accepted, independently matching the reviewer's reproduction.
Swipe tests confirmed to be genuine guards, not decoration: the F1 case uses `dx=50, dy=200`, which
sits exactly at the old `SWIPE_PX` threshold and *would* advance without the direction check; the F2
case lifts a second finger 300px from finger 1's origin.

**Task 10 is COMPLETE.** ✅

- [x] Task 10 — TradeDeck ✅ complete (`5d82139` + `5c1fc39`, reviewed)
- **NEXT: Task 11 — Overview (KPI tiles, equity curve, EMA-distance slices).** Nothing blocks it.
  Implement via sonnet-worker, then the mandatory per-task review before Task 12. Note the `dataviz`
  skill applies to the equity curve and KPI tiles — load it before writing chart code.
- Task 12 — wire run picker + tabs, styling via the frontend-design skill, build, deploy

Then: final whole-branch review (most capable model), then finishing-a-development-branch.

### Deferred Minor findings

Held for the final whole-branch review to triage, not lost:
- `marks.ts` POST accepts any non-empty `bar_ts` — no ISO-8601 shape check.
- `0002_marks.sql` `idx_marks_signal` is redundant with the `UNIQUE(signal_key, kind)` index.
- Frontend tabs lack `aria-controls`/`tabpanel` wiring and roving tabindex.
- `dashboard/tsconfig.json` and `frontend/tsconfig.json` duplicate six compiler options.
- No test covers a scaled viewport (`rect.width !== 320`) for candle tap hit-testing.
- `priceRange` extras omit `exit_price` (a gap-through exit could render off-canvas).
- `barIndexAtOrAfter` on an empty bars array returns 0, which would make `focusWindow` invert.
- `pull-feedback.ts` `--remote` path and a non-empty feedback table are untested.
- `mergeMarksKeepingOverrides` (`useAnnotations.ts:47-50`) has an undocumented dependency on the DB's
  `UNIQUE(signal_key, kind)` constraint — one-line comment, pre-existing, not widened by `9957345`.
- TradeDeck's `{index + 1} of {list.length}` has no `aria-live`, so a swipe (no focus change) is
  never announced; the loading↔chart swap has no `aria-busy` (Task 10 review, F7).

### Environment facts that survive the session

- Worker: <https://axe-trader-dashboard.g3tech.workers.dev> — Cloudflare Access gates every route
  (unauthenticated requests get a 302, including `/api/health`).
- D1: `axe-trader-dashboard`, id `fd847584-8fd9-427e-b869-9423a6c5b419`, region WEUR, free tier.
  Holds the `emaCeil_3,0atr` run: 102 trades, 88% win rate.
- Commands run from `dashboard/`: `npm test` (api + ui), `npm run test:ui`, `npm run typecheck`,
  `npm run build`, `npm run dev:ui`, `npm run deploy` (builds first).
- `@cloudflare/vitest-pool-workers` is pinned to `0.18.4` — newer patches depend on miniflare
  builds that are not published. vitest is v4; the config API is the `cloudflareTest()` plugin.
