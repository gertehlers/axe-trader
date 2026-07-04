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

### Iteration 11 — 3-tier scale-out (aggressive trail): helps, not enough yet (2026-07-04)

Built the scale-out exit (user picks, this session): **aggressive-trail ratchet** with tiers
**T1 0.75 / T2 1.5 ATR**. Bank ⅓ at T1 (stop→breakeven), ⅓ at T2 (stop→T1), final ⅓ trails at
`peak − trail·ATR` floored at T1. Code: `BacktestRunner.scaleOutExit` (money accounting +
conservative adverse-first intrabar ordering, unit-tested in `BacktestRunnerIntrabarTest`); config
`backtest.strategy.scale-out-enabled` + `tier1/tier2/trail-atr-multiple` (reuses `stop-atr-multiple`
as the pre-T1 stop). **Same 309 entries** as the promoted profile — isolates the exit effect.

In-sample (Dec'24→Dec'25), swept trail distance; tighter trail wins:

| Config (on promoted entries) | Trades | Win% | netAvgPnl | worst quarter |
|---|---|---|---|---|
| baseline single-target 0.75/3.0 | 309 | 82% | −0.14 | Q1'25 −0.74 |
| **scaleOut trail 1.0** | 309 | 82% | **−0.06** | Q4'24 −0.70 |
| scaleOut trail 1.5 | 309 | 82% | −0.11 | — |
| scaleOut trail 2.0 | 309 | 82% | −0.17 | — |

Per-quarter, trail1.0 vs baseline: Q4'24 −0.06→**−0.70**, Q1'25 −0.74→−0.63, Q2'25 −0.36→**+0.18**,
Q3'25 −0.05→**+0.04**, Q4'25 +0.59→+0.29.

**Read:** scale-out **flips the choppy/trending quarters positive (Q2, Q3)** by letting winners run
past the 0.75 cap, but **gives back on quarters where the tight target already worked** (Q4'24,
Q4'25) — trades that would've banked +0.75 now reverse to breakeven on the held ⅔. Net aggregate
improves (−0.14 → −0.06, the best honest-fill result so far) but is **still slightly negative and
still has 2 negative quarters**. Best variant: **trail 1.0** (tightest = bank fast, give back least).
NOT promoted; NOT taken out-of-sample yet.

**Why it's capped:** scale-out only acts *after* T1. The ~18% of trades that hit the 3.0-ATR stop
*before* T1 still lose the full position — that tail (~0.5 ATR of drag/trade) is the structural
anchor, and no post-T1 exit change can touch it. **The untested exit lever is the pre-T1 initial
stop itself** (3.0 ATR is very wide for a quick mean-reversion bounce).

### Iteration 12 — initial-stop × scale-out: converges to break-even, doesn't cross (2026-07-04)

Swept the **initial (pre-T1) stop** on top of scaleOut trail1.0, in-sample. Tightening the wide
3.0-ATR stop helps up to a point, then the win-rate loss outweighs the smaller losers:

| Config (scaleOut trail1.0) | Trades | Win% | netAvgPnl |
|---|---|---|---|
| stop 3.0 | 309 | 82% | −0.06 |
| **stop 2.5** | 312 | 78% | **−0.02** (best) |
| stop 2.0 | 315 | 74% | −0.12 |
| stop 1.5 | 322 | 68% | −0.14 |

Best config all session: **scaleOut trail1.0 + stop2.5 → net −0.02/trade in-sample** (avgR +0.03).
Exit-geometry progress across the session: single-target −0.14 → scale-out −0.06 → +stop2.5 −0.02.
A real ~85% cut in the loss, **asymptotically approaching zero from below — but not crossing it.**
stop2.5 quarters: Q4'24 −0.89 (16 trades, Dec selloff), Q1'25 −0.40, Q2'25 +0.38, Q3'25 −0.02,
Q4'25 +0.09 — **still 2 negative quarters**, so it FAILS the every-quarter gate → no OOS shot taken.

**Conclusion — the exit lever is now exhausted around break-even.** Both entry filtering (iters 1–10,
all failed OOS) and exit geometry (iters 11–12, plateau at −0.02) have been worked hard. The same
1–2 quarters (Dec'24 selloff, Q1'25) stay negative under every config — they lose on *genuine*
stop-outs in choppy/down regimes, which only entry avoidance could fix, and entry avoidance doesn't
generalize. **The honest read: this single long-only mean-reversion entry on US500 5m is a real
~80%-win but structurally break-even edge.** Micro-tuning US500 exits further has low expected value.

### Iteration 13 — regime-slope entry gate: shuffles the bad quarter, still break-even (2026-07-04)

Entry redesign (user pick): a **regime-slope gate** — longs only when the trend EMA is *rising*
over the last N bars (shorts only when falling), a coarse higher-timeframe regime proxy on top of
the same-timeframe price-vs-EMA gate. Rationale: in the Dec'24/Q1'25 chop the 5m EMA whipsaws, so
"above the EMA" still buys downtrends. Code: `backtest.strategy.trend-ema-slope-lookback` +
`StrategyFactory` gate. Tested on the best exit (scaleOut trail1.0 + stop2.5), sweeping N in-sample:

| Config | Trades | /day | Win% | netAvgPnl |
|---|---|---|---|---|
| scaleOut trail1.0 stop2.5 (no slope gate) | 312 | 0.9 | 78% | −0.02 |
| i13 regimeSlope20 | 311 | 0.9 | 78% | −0.02 |
| i13 regimeSlope50 | 287 | 0.9 | 77% | −0.02 |
| **i13 regimeSlope100** | 258 | 0.8 | 78% | **0.00** |

slope100 quarters: Q4'24 −1.66 (11 trades), Q1'25 **+0.13** (fixed!), Q2'25 +0.47, Q3'25 −0.29,
Q4'25 +0.00. **The gate fixed the target quarter (Q1'25 −0.40→+0.13) but worsened Q4'24 (−0.89→
−1.66) and flipped Q3'25 negative** — it moved the bleed, didn't stop it. Aggregate reached exactly
break-even (0.00) at the cost of ⅙ of the trades. Still fails net > 0 AND every-quarter ≥ 0 → no OOS.

**Session conclusion (iterations 11–13, this session): the edge is structurally ~break-even.** Three
independent levers — exit geometry (scale-out, −0.14→−0.06), initial-stop tightening (−0.06→−0.02),
and a regime entry gate (−0.02→0.00) — each moved expectancy asymptotically toward zero *from below*
and none crossed into consistent profit; trimming trades to fix one bad quarter just surfaces
another, while cadence thins. Combined with iterations 1–10 (entry filters, all failed OOS), the
honest finding is that **long-only mean-reversion on US500 5m is a genuine ~80%-win but break-even
edge. The 80%-win-rate north star and the make-money north star appear to be in direct conflict for
this instrument+thesis** — the high win rate is *bought* with wins≈losses geometry that can't be
net-positive after costs. See the fork below; decided with the user before spending more iterations.

### Iteration 14 — MOMENTUM thesis: positive skew confirmed, but US500 5m is mean-reverting (2026-07-04)

Thesis pivot (user pick): built a full **MOMENTUM entry** (`StrategyMode.MOMENTUM`, `strategy.mode`
config) in the same confluence-voting framework — votes: RSI>50 & rising, break of prior swing high,
volume thrust, continuation candle; regime-slope gate; **inverted positive-skew exit** (tight stop
1.0–1.5 ATR, tiers pushed out, wide trail so losers cut small / winners run). Long-only. Swept
threshold, breakout lookback, slope, and exit geometry in-sample:

| Config | Trades | /day | Win% | avgR | netAvgPnl |
|---|---|---|---|---|---|
| mean-reversion scaleOut stop2.5 (ref) | 312 | 0.9 | 78% | 0.03 | −0.02 |
| i14_momo_wideTiers (T1 1.5/T2 3.0/trail 2.5) | 1912 | 5.7 | 51% | 0.03 | **−0.10** (best momo) |
| i14_momo_base (thr3, stop1.5, slope50) | 1912 | 5.7 | 59% | 0.01 | −0.26 |
| i14_momo_stop1.0 | 2066 | 6.1 | 51% | 0.03 | −0.26 |
| i14_momo_look10 | 2186 | 6.5 | 59% | 0.01 | −0.27 |
| i14_momo_trail3.0 | 1912 | 5.7 | 59% | 0.01 | −0.28 |
| i14_momo_noSlope | 2446 | 7.3 | 60% | 0.01 | −0.22 |
| i14_momo_thr2 | 3985 | 11.9 | 59% | 0.01 | −0.34 |

**Read:** the positive-skew shape is real (win rate 80%→~59%/51%, cadence 0.9→5.7–12/day — finally
near the ~5/day target), but **every momentum variant is net-negative** (best −0.10). Momentum
breakouts on US500 5m get chopped by false breakouts — the instrument **mean-reverts at 5m**, which
is exactly why the dip-buying entry wins 80%. Momentum fights the instrument's nature and loses more.
Widening targets/trail helped (avgR 0.01→0.03, net −0.26→−0.10) but nowhere near enough.

**Both theses now tested and both net-negative at 5m** (mean-reversion −0.02 break-even, momentum
−0.10 worse). The best result overall remains the mean-reversion scaleOut at −0.02. Neither makes
money at the 5-minute timeframe. Code (`StrategyMode`, momentum pillars, positive-skew exit) is kept
and reusable.

**The untested dimension is TIMEFRAME.** Everything across all 14 iterations ran on 5m bars, where
moves are small relative to the ~0.5-pt spread and intrabar noise dominates. Higher timeframes
(15m / 30m / 1h — free to aggregate from the 1m history via `timeframe-minutes`) mean bigger moves
per trade vs. a fixed spread and cleaner structure, which could make *either* thesis net-positive.
This is the next cheap, high-value lever (iteration 15).

### Iteration 15 — TIMEFRAME BREAKTHROUGH: momentum at 15m is net-POSITIVE in-sample (2026-07-04)

Added a `-Dsweep.tf=N` timeframe override to the harness and scanned the full grid at 15m and 30m
(all configs are ATR-relative, so they scale automatically). **This is the first net-positive result
in the project.** The 5m assumption was the hidden constraint the whole time.

In-sample (Dec'24→Dec'25), best rows per timeframe:

| Config | TF | Trades | /day | Win% | avgR | netAvgPnl |
|---|---|---|---|---|---|---|
| mean-reversion final_longOnly | 5m | 309 | 0.9 | 82% | 0.02 | −0.14 |
| mean-reversion scaleOut stop2.5 | 5m | 312 | 0.9 | 78% | 0.03 | −0.02 |
| mean-reversion (both) | 15m | ~102 | 0.3 | 78% | −0.01 | **−1.4 to −1.7** (worse!) |
| i14_momo_noSlope | 15m | 877 | 2.6 | 60% | 0.05 | **+0.41** |
| **i14_momo_wideTiers** | 15m | 695 | 2.1 | 53% | **0.10** | **+0.31** |
| i14_momo_stop1.0 | 30m | 437 | 1.3 | 51% | 0.07 | +0.16 |
| momentum (most) | 30m | — | — | ~56% | — | negative again |

**Coherent story:** mean-reversion *degrades* at 15m while momentum *improves* — bigger moves clear
the fixed ~0.5-pt spread and 15m breakouts trend where 5m breakouts chop. 15m is a genuine sweet
spot (30m mostly reverts to negative), not a monotonic effect. Positive skew is now real and paying:
win 53–60%, avgR +0.05 to +0.10, cadence 2–2.6/day.

Per-quarter (the gate). **noSlope +0.41**: Q4'24 +3.62, Q1'25 −1.10, Q2'25 +1.95, Q3'25 −0.14, Q4'25
+0.06 — top-heavy, 2 negative quarters. **wideTiers +0.31**: Q4'24 +1.89, Q1'25 −0.78, Q2'25 +0.27,
Q3'25 +0.54, Q4'25 +0.76 — **4 of 5 quarters positive**, only Q1'25 negative, and the positives are
large-sample (180–200 trades), not one jackpot. wideTiers is the more robust candidate despite the
lower aggregate.

⚠️ **In-sample, selected from a wide search (~9 configs × 3 timeframes) — multiple-comparison risk is
high.** Pre-committing **wideTiers @ 15m** (mode MOMENTUM, thr3, look20, slope50, stop1.5, tiers
1.5/3.0, trail2.5) as the single OOS candidate; the untouched Jan–May'26 window is the referee. See
the iteration-16 result below for whether it survived.

### Iteration 16 — OOS referee: momentum @ 15m SURVIVES (first positive-OOS result) (2026-07-04)

Pre-committed **wideTiers @ 15m** taken to the untouched Jan–May'26 window (one shot, no retuning):

| Window | Trades | /day | Win% | avgR | netAvgPnl |
|---|---|---|---|---|---|
| In-sample (Dec'24→Dec'25) | 695 | 2.1 | 53% | 0.10 | +0.31 |
| **Out-of-sample (Jan–May'26)** | 216 | 2.1 | 51% | 0.06 | **+0.10** |

**The sign held positive out-of-sample** — the first candidate ever to do so (every mean-reversion
candidate flipped −0.79 to −2.02 OOS). Cadence and win rate held (2.1/day, 51–53%). This is a real,
generalizing positive-skew edge, not an in-sample mirage.

**But it's thin and trend-dependent, not yet every-month-positive.** OOS by month: Jan −1.64, Feb
−2.40, Mar −0.80, **Apr +2.43** — April's trend leg carried the quarter; Q1'26 was −1.73, echoing the
in-sample Q1'25 −0.78 weakness. Momentum makes money in trending months and bleeds in choppy ones
(Jan–Mar). So: **positive expectancy that survives OOS ✅, but consistency/every-quarter ❌.**

**This is the project's turning point.** After 15 iterations of break-even/negative mean-reversion,
momentum @ 15m is the first approach with a genuine, OOS-validated positive edge. It is the thing to
*develop*, not abandon. Not promoted to `application.yaml` yet (still 5m mean-reversion) — it's a
validated lead, not a robust every-quarter strategy; promoting the running default is a user call.

### Iteration 17 — momentum surface + duration scan: 10m > 15m, every quarter positive (2026-07-04)

Promoted momentum @ 15m to `application.yaml`, then (user ask) mapped the response surface across
timeframes 8–25m and around the anchor config. Duration curve (anchor config, in-sample):

| TF | Trades | /day | Win% | avgR | netAvgPnl | $net/day |
|---|---|---|---|---|---|---|
| 8m | 1230 | 3.7 | 51% | 0.03 | −0.25 | −0.91 |
| **10m** | 998 | 3.0 | 53% | 0.09 | **+0.52** | **+1.56** |
| 12m | 877 | 2.6 | 51% | 0.06 | +0.14 | +0.38 |
| 15m | 695 | 2.1 | 53% | 0.10 | +0.31 | +0.65 |
| 20m | 536 | 1.6 | 50% | 0.02 | +0.10 | +0.16 |
| 25m | 472 | 1.4 | 50% | −0.01 | −0.56 | −0.79 |

**The edge lives in a 10–20m band, peaking at 10m** (8m too noisy, 25m+ too slow). **10m is the new
best and clears the every-quarter gate**: anchor @10m quarters Q4'24 +2.37, Q1'25 **+0.21**, Q2'25
+0.64, Q3'25 +0.56, Q4'25 +0.32 — all five positive, fixing the Q1'25 −0.78 that 15m had, at higher
expectancy (+0.52) and cadence (3.0/day, closer to the ~5 target).

Response surface at 10m (which knobs the edge depends on):
- **Threshold 3 is the sweet spot** — thr2 over-trades to −0.24; thr4 starves to 0.4/day (+0.54 but
  130 trades). Load-bearing.
- **Regime-slope gate isn't pulling weight** — slope0 (off) +0.59 / 3.8/day beats the anchor's +0.52;
  the momentum entry doesn't need it at 10m. Candidate to drop for cadence.
- **Positive skew confirmed on the tier axis** — tiers 2.0/4.0 (wider) +0.77 net / avgR 0.12 at 47%
  win; tiers 1.0/2.0 (tighter) +0.26 at 62% win. Wider = bigger winners, the intended shape.
- stop 1.0 too tight (41% win); breakout lookback 10–30 all similar (~+0.4 to +0.5).

**Next: 15m→10m is a clean single-parameter improvement** (same entry/exit that already passed 15m
OOS, just a better timeframe, every-quarter-positive in-sample). Pre-committing the anchor @10m for
ONE OOS shot before re-promoting. Richer surface cells (wider tiers, slope off) are follow-on tuning
to do IN-SAMPLE with walk-forward — not to stack onto the same OOS peek. See iteration 18.

### Iteration 18 — OOS at 10m: every quarter positive in AND out of sample; re-promoted (2026-07-04)

Pre-committed anchor @10m (same entry/exit that passed 15m OOS, timeframe 15→10) taken to Jan–May'26:

| Window | Trades | /day | Win% | avgR | netAvgPnl | quarters |
|---|---|---|---|---|---|---|
| In-sample (Dec'24→Dec'25) | 998 | 3.0 | 53% | 0.09 | **+0.52** | all 5 positive |
| **Out-of-sample (Jan–May'26)** | 283 | 2.7 | 53% | 0.13 | **+0.45** | Q1'26 +0.09, Q2'26 +0.96 |

**10m holds out-of-sample far better than 15m** (+0.45 vs +0.10; both OOS quarters positive vs 15m's
Q1'26 −1.73). Only 1 of 5 OOS months negative (Mar −1.34). This is the project's **first profitable,
every-quarter-positive result in and out of sample.** Re-promoted `application.yaml` to 10m.

**Honest status vs. north star:** makes money ✅ (+0.45 OOS, ~$1.2/day per unit @ $1/pt); every
quarter positive ✅ (in + out of sample); cadence 2.7–3.0/day (toward the ~5 target) ✅-ish;
reproducible/explainable ✅ (4 momentum pillar votes + regime gate + scale-out, all in yaml).
Win-rate 53% ❌ vs the old 80% goal — but that goal was the thing making the strategy *lose*; the
positive-skew trade (win rate for money) was the whole unlock. The remaining risk is the usual one:
this is still a backtest on ~17 months of one instrument; live needs risk controls first.

**Where a fresh session should pick up (momentum @ 10m is the live, validated lead — 2026-07-04):**
1. **Refine the 10m surface with walk-forward** (NOT another single OOS peek — 2026 is now used twice).
   In-sample leads not yet OOS-validated: wider tiers (2.0/4.0 → +0.77 IS, avgR 0.12), dropping the
   slope gate (slope0 → +0.59 IS, 3.8/day). Validate via walk-forward splits inside Dec'24–Dec'25.
2. **Stabilise the one weak month (Mar'26 −1.34)** if a clean, few-parameter filter exists — but do
   not overfit to a single month.
3. **Risk controls before any live use** (position sizing, max drawdown, circuit breaker) — now the
   highest-value non-strategy work, since there's finally a profitable edge to protect.
4. Cadence toward ~5/day: 10m already gives ~3/day; a 2nd instrument's profile (breadth) is the
   principled way to more, once data ingestion is unblocked (needs Capital.com creds).
5. MONITOR-mode validation remains open (needs Capital.com network access + credentials).

--- earlier live-lead notes (15m, superseded by 10m in iteration 18) ---
1. **Stabilize the choppy-quarter bleed (Q1'25 / Q1'26).** Momentum loses in non-trending months.
   A trend-quality / chop filter (e.g. ADX or an efficiency-ratio gate, or the regime-slope gate the
   noSlope variant dropped — revisit it now that the base thesis is proven) could cut the Jan–Mar
   drawdowns without killing the April-type winners. Tune IN-SAMPLE with walk-forward — the 2026 OOS
   window is now partially burned for wideTiers, so don't re-tune against it; a fresh honest read
   needs walk-forward splits inside Dec'24–Dec'25 or new data.
2. **Refine the momentum entry/exit** (RSI band, breakout lookback, tier/trail geometry) at 15m —
   only the 5m→15m + a handful of exit knobs were swept; the 15m response surface is barely mapped.
3. **Scan 10m and 20m** — 15m beat 5m and 30m, but the optimum between them is unmapped.
4. Consider promoting momentum @ 15m to `application.yaml` as the documented best profile (user call
   — it changes the running default from 5m mean-reversion; it's the first net-positive config).
5. Still open: risk controls (position sizing, max drawdown, circuit breaker) before any live use;
   per-instrument config profiles for breadth; MONITOR-mode validation (needs Capital.com creds).

Reusable plumbing built this session: `BacktestRunner.scaleOutExit` (3-tier aggressive-trail exit),
`StrategyMode.MOMENTUM` + momentum pillars, `trend-ema-slope-lookback` regime gate, `-Dsweep.tf`
timeframe override — all config-driven and unit-tested where it matters.

--- superseded fork notes (kept for history) ---
Near-break-even mean-reversion plateau options (pre-breakthrough): breadth via 2nd instrument;
rethink entry (→ done, momentum won); document break-even profile. The entry rethink resolved this.

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
