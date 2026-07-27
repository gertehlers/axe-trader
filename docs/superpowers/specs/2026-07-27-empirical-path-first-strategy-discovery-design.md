# Empirical Path-First Strategy Discovery

**Status:** approved design

**Date:** 2026-07-27

**Instrument:** US500, five-minute bars

**Decision:** replace theory-first tuning with an every-bar, path-first discovery workflow. Keep the
five pillars as an explainable vocabulary, but do not require the current strategy to nominate an
entry before a bar can be studied.

## 1. Purpose

The current strategy often enters during chop and misses sustained moves. The review UI makes those
mistakes visible, but manually marking better entries and exits only restates what is already plotted.
The next system must inspect every eligible bar itself, find where executable long and short
opportunities existed, examine the backward-looking pillar and market state at those points, and
extract repeatable patterns.

The primary objective is no longer win rate. Candidates are ranked by net profit after transaction
costs, constrained by drawdown and consistency across calendar months. Win rate remains a diagnostic.

This is empirical discovery, not black-box live trading. Hindsight is allowed only in a physically
separate label layer that describes what happened after a bar. Every deployable entry or exit rule
must use information available at the time it acts.

## 2. Agreed operating rules

- Evaluate every completed five-minute bar independently in both directions:
  `bar × LONG` and `bar × SHORT`.
- Permit overlapping hypothetical observations during discovery. Two entries ten minutes apart may
  have materially different outcomes and must remain distinct.
- Do not calculate portfolio profit from overlapping discovery observations.
- During executable validation, use chronological next-bar fills and allow only one open position
  per instrument.
- The maximum holding period is four hours.
- If Capital.com's executable US500 trading session closes first, exit at the final executable bar.
  The US cash-market close is not a trading cutoff while the broker's instrument remains tradable.
- Learn long and short patterns separately, then report their combined executable result.
- Permit richer backward-looking measurements beneath the five pillars, including distances, slopes,
  rates of change, transitions, persistence, and activation order. Final candidates must remain
  compact and explainable.
- Preserve Jan–May 2026 as the untouched, one-shot final out-of-sample window.

## 3. Non-goals

- Fitting a strategy toward manually marked entries or exits.
- Treating the exact hindsight low or high as an achievable live signal.
- Optimising a generic three-tier ladder across all trades.
- Using overlapping discovery examples as if they were independent trades.
- Deploying a machine-learning model as an opaque entry signal.
- Adding portfolio sizing, leverage, or live execution in this round.
- Spending the final OOS window during discovery or implementation testing.

## 4. Core data model

### 4.1 Observation identity

One observation is identified by:

```text
instrument | timeframe | completed-bar timestamp | direction
```

An observation contains two separate records joined by that identity:

1. `ObservableState`: backward-only information available at the completed signal bar.
2. `ForwardPathLabel`: future path and counterfactual outcomes, available only to offline discovery.

The types, storage columns, and APIs for these records must remain separate. Strategy and executable
validation code may depend on `ObservableState`; it must not depend on `ForwardPathLabel`.

### 4.2 Observable state

The observation extractor calculates the following for every eligible bar:

- raw measurements underlying all five pillars;
- current boolean pillar votes and confluence count;
- pillar activation, deactivation, strengthening, and weakening;
- pillar persistence and recent agreement;
- RSI and its recent slope;
- Bollinger-band and EMA distances in ATR units;
- support/resistance distances and recent changes;
- trend slope and acceleration;
- candle anatomy and recent return path;
- ATR, ATR percentile, volatility contraction/expansion, and recent changes;
- volume ratio, volume change, and price/volume agreement;
- hour, day of week, and minutes until the broker's actual trading close;
- current values plus lags of 1, 2, 3, 6, and 12 bars where applicable.

The lag set represents 5, 10, 15, 30, and 60 minutes. Feature calculations may be refactored out of
the existing trade-only capture, but the strategy runner and discovery extractor must share the same
definitions so that offline and live values cannot drift.

Bars without sufficient indicator warm-up, a resolvable trading-session boundary, or an executable
next bar are excluded with an explicit reason.

### 4.3 Execution convention

A state is observed only after its bar completes. An executable candidate fills on the next available
bar, using direction-appropriate bid/ask prices where the historical source provides them. If the
engine uses a configured spread model instead, the cost is applied exactly once. The same convention
must be shared by the forward labeller and executable validator.

### 4.4 Forward path

The label window begins at the executable next-bar fill and ends at the earlier of:

- 48 five-minute bars after entry; or
- the final executable bar before Capital.com's US500 trading close.

The label stores the raw executable path plus:

- net return after costs at 5, 15, 30, 60, 120, and 240 minutes where available;
- maximum favourable excursion (MFE);
- maximum adverse excursion (MAE);
- MAE before MFE and the ordering of material excursions;
- time to MFE, MAE, and candidate price levels;
- favourable movement surrendered after the peak;
- path two-sidedness and directional efficiency;
- remaining time to trading close;
- fill outcomes for a bounded library of feasible stop, tier, ratchet, invalidation, and time exits.

Excursions and target/stop distances are stored both in price points and in entry-time ATR units.
Cross-observation percentile calculations use ATR-normalised values; final reports show both.

The raw path measurements are the source of truth. Derived opportunity scores and classifications are
versioned analysis outputs so their formulas can change without regenerating observable features.

### 4.5 Session and gap handling

Trading close means the end of the broker's executable session for the instrument, not 16:00 New York
time. The resolver uses the instrument's trading calendar/availability captured from the broker and
cross-checks it against historical executable bars.

- A known session boundary truncates the label normally.
- A data gap that is not a known boundary truncates the observation and marks its label incomplete.
- An unknown boundary fails closed; it is never guessed from the cash-market clock.
- Incomplete labels may be inspected for data quality but cannot train or validate a candidate.

## 5. Opportunity map and entry discovery

### 5.1 Multi-dimensional entry quality

The first analysis does not reduce each bar to one binary "best entry" label. It preserves:

- net favourable opportunity after costs;
- adverse movement before the opportunity;
- reward relative to adverse movement;
- speed of follow-through;
- surrendered favourable movement;
- path directionality versus violent two-sided movement;
- time remaining before trading close.

These measures distinguish sustained runs, chop/false starts, and ambiguous paths without pretending
that one perfect bottom or top was knowable. Adjacent high-quality bars may be grouped into an
`OpportunityZone` for reporting and sample accounting, but their original observations and outcomes
remain distinct.

### 5.2 Pattern extraction

Pattern discovery proceeds from simple evidence to small interactions:

1. Produce baseline and conditional outcome tables for each feature, transition, and bucket.
2. Examine activation order, strengthening/weakening, volatility transitions, and price/volume
   confirmation.
3. Use decision trees of maximum depth three and compact rule lists as offline pattern finders.
4. Convert promising combinations into explicit backward-only candidate rules.
5. Register each candidate before evaluating it on the next chronological monthly fold.

Each final candidate contains at most four clauses. Long and short candidates are separate even when
they use similar features.

### 5.3 Independence and false-discovery controls

- Adjacent qualifying bars from the same sustained move form one opportunity zone for evidence
  counts.
- For a given scoring version and direction, qualifying bars belong to the same zone while they are
  no more than one bar apart and their MFE timestamps are no more than six bars apart. Two
  consecutive non-qualifying bars or an MFE-timestamp separation above six bars closes the zone.
- A pattern needs at least 30 independent development zones in its direction before promotion.
- Correlated threshold variants are recorded as one pattern family, not many apparent discoveries.
- At most ten entry-pattern families per direction may advance from initial discovery.
- A sampled discovery month contains at least five non-overlapping executable trades for the
  candidate.
- Discovery requires positive total net expectancy and at least 50% profitable sampled months.
- All monthly results, including months below the sample threshold, remain visible in reports.

These permissive discovery gates avoid throwing away a genuine edge early. They do not qualify a
candidate for final OOS validation.

## 6. Pattern-specific exit discovery

Exits are derived separately for each entry-pattern family. For every occurrence, analysis produces:

- probability of reaching each favourable level before each adverse level;
- continuation probability after reaching candidate T1 and T2 levels;
- reversal risk as holding time increases;
- conditional MFE/MAE distributions;
- observable pillar changes preceding continuation or reversal;
- remaining time to trading close;
- capture ratio for each executable policy versus the hindsight opportunity.

### 6.1 Oracle and executable exits

`OracleExit` is the best feasible hindsight outcome on one labelled path. It establishes an upper
bound and identifies where opportunity was surrendered. It is never installed as a trading rule.

`ExecutableExitPolicy` is one frozen, backward-only policy applied to every occurrence of its entry
pattern. It may contain:

- one to three profit tiers;
- tier prices selected from the 25th, 50th, and 75th percentiles of positive development MFE;
- fractions selected from the predeclared templates `100%`, `50/50`, `33/67`, `67/33`,
  `33/33/34`, `25/35/40`, and `50/25/25`;
- a structural or empirically bounded initial stop;
- an optional ratchet;
- a compact pattern-invalidation rule;
- the mandatory four-hour/trading-close exit.

Initial-stop candidates are the entry-time structural invalidation level and the 50th and 75th
percentiles of development MAE-before-MFE. Ratchets are limited to none, breakeven after the first
tier, and previous-tier after the next tier. The generator ranks its predeclared combinations on the
derivation months by net-profit-to-drawdown ratio and emits no more than twelve policies for one
entry-pattern family. Those policies are frozen before later folds are read.

The system must be allowed to conclude that one exit beats tiering. Tiers are retained only when they
add net value and improve or preserve risk for their particular entry family.

### 6.2 Dynamic invalidation

A policy may close some or all remaining size when observable post-entry state deteriorates. An
invalidation rule contains at most two conditions, such as loss of trend slope plus weakening volume.
It is evaluated after a bar completes and filled at the next executable price. Stop and touched price
tiers continue to use the runner's conservative intrabar ordering.

## 7. Architecture

### 7.1 Observation extractor (Java)

Reads the existing `BarSeries`, `IndicatorBundle`, and pillar definitions and emits
`ObservableState` for every eligible bar-direction. This component owns no future traversal.

### 7.2 Forward-path labeller (Java)

Starts from the shared next-bar execution convention, walks no more than four hours or the known
trading close, emits `ForwardPathLabel`, and evaluates the bounded exit-policy primitives using the
same fill rules as `BacktestRunner`.

### 7.3 Pattern analyser (offline)

Reads the discovery database and produces opportunity zones, conditional slices, compact entry
families, pattern-specific exit candidates, and chronological fold reports. It cannot place trades
or claim portfolio P&L from overlapping observations.

### 7.4 Executable validator (Java)

Loads a frozen candidate definition and runs chronologically with:

- next-bar fills;
- one position per instrument;
- exact transaction-cost convention;
- conservative intrabar ordering;
- four-hour maximum hold;
- mandatory final executable-bar exit at trading close.

Only this component may report candidate strategy P&L.

### 7.5 Versioned discovery store

A local SQLite database stores:

- observation metadata and observable features;
- forward-path labels;
- opportunity zones and membership;
- pattern families and frozen candidate definitions;
- entry and exit derivation windows;
- monthly fold results;
- executable policy comparisons;
- data-window identity, source commit, configuration hash, and schema version.

The generated database is a reproducible experiment artifact, not a new application database.

## 8. Validation workflow

### 8.1 Development folds

All discovery and policy derivation use chronological development history only. Each candidate is
derived on earlier months and evaluated without modification on the next month. Fold results are
then accumulated; a failed month may inform a new candidate family, but the failed candidate's result
is never rewritten.

### 8.2 Promotion gate

A frozen candidate may be nominated for the final OOS shot only when executable, non-overlapping
development results satisfy all of:

- positive total net profit after costs;
- positive median monthly net profit;
- at least 70% of sampled months profitable;
- at least six sampled development months;
- at least ten non-overlapping trades in each sampled promotion month;
- total net profit divided by maximum drawdown of at least 1.0;
- no configuration or rule changes after nomination.

Long-only, short-only, and combined candidates are eligible. A combined candidate must report each
direction separately, and each enabled direction must have positive total development net profit. If
one direction fails, the profitable direction may be nominated on its own without changing its rule.

### 8.3 Final OOS gate

Jan–May 2026 remains inaccessible to normal discovery commands. A separate explicit final-validation
command records that the one-shot window has been spent.

The OOS result passes only if:

- total net profit after costs is positive;
- median monthly net profit is positive;
- at least 50% of sampled OOS months are profitable;
- maximum drawdown divided by executed trade count is no worse than 1.5 times the same development
  ratio;
- no parameter, feature, direction, entry rule, or exit rule is changed after seeing the result.

An OOS month is sampled when it contains at least ten non-overlapping executable trades, matching the
promotion-month definition. Months below that threshold remain visible and make the sample-size
limitation explicit.

A failure is recorded as a falsified candidate. Retuning requires a new future holdout; Jan–May 2026
cannot referee another descendant of the failed rule.

## 9. Reporting and dashboard

The complete every-bar dataset remains local. The Cloudflare dashboard receives compact, versioned
reports containing:

- ranked entry-pattern families;
- baseline versus conditional outcomes;
- independent opportunity-zone counts;
- monthly walk-forward performance;
- long, short, and combined results;
- oracle opportunity versus executable capture;
- selected best, median, worst, false-positive, and missed-run examples;
- pillar values, transitions, and rule clauses visible on each example;
- pattern-specific exit curves and candidate policy comparison.

Manual marks remain useful as comments and audit prompts, but never become labels or extra-weighted
training examples.

## 10. Failure handling and reproducibility

- Missing next-bar prices: exclude the observation from executable analysis.
- Missing cost inputs: fail the run rather than assume zero cost.
- Unknown trading boundary: fail closed for affected observations.
- Indicator warm-up: exclude with a counted reason.
- Incomplete forward path from an unexplained gap: retain for diagnostics, exclude from discovery.
- Empty or undersampled pattern: report it; do not relax the gate automatically.
- Duplicate observation identity: fail generation.
- Any attempt by a strategy component to load label columns: fail at the interface boundary.
- Any ordinary command targeting the protected OOS interval: reject it.

Every report carries the source commit, input-data identity, timeframe, feature schema, scoring
version, configuration hash, and generation timestamp. Given identical inputs, observation rows,
labels, zones, candidate rankings, and validation results must be identical.

## 11. Testing

### Observation and leakage

- Mutating future bars changes `ForwardPathLabel` but not `ObservableState`.
- Lagged features use only completed bars and have exact expected timestamps.
- Trade-only and every-bar feature calculations agree for the same signal bar.
- Long and short observations are both emitted for every eligible bar.

### Execution and labels

- Long and short paths use the correct side of bid/ask data.
- Costs are applied exactly once.
- The 48-bar limit is exact.
- A broker trading close truncates the path and forces the correct final fill.
- A non-session data gap produces an incomplete label.
- Conservative stop/tier tie-breaking matches existing runner tests.

### Entry discovery

- Overlapping observations are retained.
- Adjacent good observations collapse to one independent opportunity zone.
- Two bars ten minutes apart retain distinct scores and features.
- Rule clauses reference only observable fields.
- Candidate and family caps are enforced deterministically.

### Exit discovery

- Hand-built paths verify oracle bounds and executable policy P&L.
- One-, two-, and three-tier policies calculate size-weighted P&L correctly.
- Dynamic invalidation acts on bar completion and fills next bar.
- A one-exit policy can outrank tiered candidates.

### Validation and OOS protection

- Executable validation never overlaps positions.
- Monthly folds never derive from their evaluation month.
- Months below the sample threshold are visible but do not count toward the percentage gate.
- Promotion metrics match hand-calculated fixtures.
- Normal commands cannot read Jan–May 2026.
- Final validation appends a `window_spent` event to the discovery store, refuses a second run for
  that window, and emits a result record suitable for the repository's durable tuning log.

### Determinism

- Repeating generation and analysis with identical inputs yields identical rows and rankings.
- Changing the data, feature schema, scoring version, configuration, or source commit changes the
  recorded run identity.

## 12. Acceptance criteria

The first complete discovery round is successful when it:

1. emits leak-safe long and short observations for every eligible development bar;
2. produces a reviewable empirical opportunity map independent of current strategy entries;
3. identifies and explains compact entry-pattern families with independent-zone counts;
4. derives bounded, pattern-specific exit candidates, including the possibility of no tiers;
5. validates frozen candidates chronologically with no overlapping positions;
6. reports net and risk performance by calendar month;
7. protects Jan–May 2026 until a candidate passes the promotion gate;
8. makes negative results durable rather than silently loosening gates or retuning.

Implementation begins only after this spec is reviewed, followed by a separate implementation plan.
