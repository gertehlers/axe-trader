package io.g3tech.axetrader.backtest.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.g3tech.axetrader.backtest.discovery.analysis.CandidateRule;
import io.g3tech.axetrader.backtest.discovery.analysis.OpportunityClass;
import io.g3tech.axetrader.backtest.discovery.analysis.OpportunityScore;
import io.g3tech.axetrader.backtest.discovery.analysis.OpportunityScorerV1;
import io.g3tech.axetrader.backtest.discovery.analysis.OpportunityZone;
import io.g3tech.axetrader.backtest.discovery.analysis.OpportunityZoneBuilder;
import io.g3tech.axetrader.backtest.discovery.analysis.ShallowRuleMiner;
import io.g3tech.axetrader.backtest.discovery.exit.ExitPolicy;
import io.g3tech.axetrader.backtest.discovery.exit.ExitPolicyGenerator;
import io.g3tech.axetrader.backtest.discovery.model.LabelledObservation;
import io.g3tech.axetrader.backtest.discovery.model.ObservableState;
import io.g3tech.axetrader.backtest.discovery.report.DiscoveryReport;
import io.g3tech.axetrader.backtest.discovery.report.DiscoveryReportExporter;
import io.g3tech.axetrader.backtest.discovery.store.DiscoveryRun;
import io.g3tech.axetrader.backtest.discovery.store.DiscoveryStore;
import io.g3tech.axetrader.backtest.discovery.validation.ExecutableValidator;
import io.g3tech.axetrader.backtest.discovery.validation.FrozenCandidate;
import io.g3tech.axetrader.backtest.discovery.validation.MonthlyResult;
import io.g3tech.axetrader.backtest.discovery.validation.PromotionGate;
import io.g3tech.axetrader.backtest.discovery.validation.ValidationStatistics;
import io.g3tech.axetrader.backtest.discovery.validation.ValidationSummary;
import io.g3tech.axetrader.backtest.discovery.validation.ValidationTrade;
import io.g3tech.axetrader.backtest.indicators.IndicatorBundle;
import io.g3tech.axetrader.backtest.runner.Direction;
import io.g3tech.axetrader.backtest.strategy.StrategyFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Coordinates the leak-safe discovery stages over a development-only, side-aware market series. */
public final class DiscoveryPipeline {

    private static final String FEATURE_SCHEMA_VERSION = "observable-v1";
    private final ObservableStateExtractor extractor;
    private final ForwardPathLabeller labeller;
    private final StrategyFactory strategyFactory;
    private final OpportunityScorerV1 scorer;
    private final OpportunityZoneBuilder zoneBuilder;
    private final RuleMining ruleMining;
    private final ExitPolicyGenerator exitPolicyGenerator;
    private final ExecutableValidator validator;
    private final PromotionGate promotionGate;
    private final DiscoveryReportExporter exporter;

    public DiscoveryPipeline(
            ObservableStateExtractor extractor, ForwardPathLabeller labeller, StrategyFactory strategyFactory) {
        this(extractor, labeller, strategyFactory, new ShallowRuleMiner()::mine);
    }

    DiscoveryPipeline(
            ObservableStateExtractor extractor,
            ForwardPathLabeller labeller,
            StrategyFactory strategyFactory,
            RuleMining ruleMining) {
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.labeller = Objects.requireNonNull(labeller, "labeller");
        this.strategyFactory = Objects.requireNonNull(strategyFactory, "strategyFactory");
        this.ruleMining = Objects.requireNonNull(ruleMining, "ruleMining");
        scorer = new OpportunityScorerV1();
        zoneBuilder = new OpportunityZoneBuilder();
        exitPolicyGenerator = new ExitPolicyGenerator();
        validator = new ExecutableValidator();
        promotionGate = new PromotionGate();
        exporter = new DiscoveryReportExporter();
    }

    public DiscoveryReport run(DiscoveryRequest request) {
        Objects.requireNonNull(request, "request");
        new DiscoveryWindowPolicy().requireDevelopmentWindow(request.from(), request.to());
        IndicatorBundle indicators = IndicatorBundle.from(request.market().mid(), request.strategyConfig());
        var strategies = strategyFactory.build(indicators, request.strategyConfig());
        DiscoveryRun run = new DiscoveryRun(
                request.inputDataHash(), configHash(request), FEATURE_SCHEMA_VERSION,
                OpportunityScorerV1.SCORE_VERSION, request.sourceCommit(), false, request.instrument(),
                request.timeframeMinutes(), request.from(), request.to());
        List<LabelledObservation> labelled = new ArrayList<>();

        try (DiscoveryStore store = DiscoveryStore.open(request.persistencePath())) {
            long runId = store.beginRun(run);
            for (int index = request.market().mid().getBeginIndex();
                 index <= request.market().mid().getEndIndex(); index++) {
                var batch = extractor.extract(
                        request.instrument(), request.timeframeMinutes(), request.market(), indicators,
                        strategies, request.strategyConfig(), request.sessionCalendar(), index);
                batch.exclusions().forEach(exclusion -> store.saveExclusion(runId, exclusion));
                for (ObservableState state : batch.states()) {
                    var label = labeller.label(
                            state.id().direction(), state.entryIndex(), state.entryAtr(),
                            request.market(), request.sessionCalendar());
                    store.saveObservation(runId, state);
                    store.saveLabel(runId, state.id(), label);
                    if (label.eligibleForDiscovery()) {
                        labelled.add(new LabelledObservation(state, label));
                    }
                }
            }

            List<OpportunityScore> scores = labelled.isEmpty() ? List.of() : scorer.score(labelled);
            List<OpportunityZone> zones = zoneBuilder.build(scores);
            zones.forEach(zone -> store.saveZone(runId, zone));

            Map<String, RegisteredCandidate> registered = new LinkedHashMap<>();
            List<ValidationTrade> allTrades = new ArrayList<>();
            List<MonthlyResultRow> monthlyRows = new ArrayList<>();
            List<YearMonth> completeMonths = completeMonths(request.from(), request.to());
            if (completeMonths.size() < 4) {
                registerCandidates(
                        request, store, runId, run.runKey(), labelled, scores, zones,
                        request.from(), request.to(), registered);
            } else {
                for (WalkForwardFold fold : walkForwardFolds(request.from(), request.to())) {
                    List<OpportunityScore> derivationScores = scores.stream()
                            .filter(score -> !score.labelledObservation().state().id().signalTime()
                                    .isBefore(fold.derivationFrom()))
                            .filter(score -> score.labelledObservation().state().id().signalTime()
                                    .isBefore(fold.derivationTo()))
                            .toList();
                    List<OpportunityZone> derivationZones = zoneBuilder.build(derivationScores);
                    List<LabelledObservation> derivation = derivationScores.stream()
                            .map(OpportunityScore::labelledObservation).toList();
                    registerCandidates(
                            request, store, runId, run.runKey(), derivation, derivationScores, derivationZones,
                            fold.derivationFrom(), fold.derivationTo(), registered);

                    Instant foldTo = fold.evaluationMonth().plusMonths(1).atDay(1)
                            .atStartOfDay(ZoneOffset.UTC).toInstant();
                    List<ObservableState> foldStates = labelled.stream()
                            .map(LabelledObservation::state)
                            .filter(state -> !state.id().signalTime().isBefore(fold.derivationTo())
                                    && state.id().signalTime().isBefore(foldTo))
                            .toList();
                    for (RegisteredCandidate candidate : registered.values()) {
                        if (candidate.frozen().derivationTo().isAfter(fold.derivationTo())) {
                            continue;
                        }
                        List<ValidationTrade> trades = validator.run(
                                candidate.frozen(), request.market(), foldStates);
                        store.saveValidation(candidate.databaseId(), trades);
                        MonthlyResult monthly = new MonthlyResult(
                                fold.evaluationMonth(), trades.size(), ValidationStatistics.totalNet(trades));
                        store.saveMonthlyResult(candidate.databaseId(), monthly);
                        allTrades.addAll(trades);
                        monthlyRows.add(new MonthlyResultRow(candidate.frozen().id(), monthly));
                    }
                }
            }

            String promotedCandidate = selectPromotionCandidate(store, runId, registered);
            DiscoveryReport report = report(
                    request, run, registered, scores, zones, allTrades, monthlyRows, promotedCandidate);
            exporter.export(request.reportPath(), report);
            return report;
        }
    }

    private void registerCandidates(
            DiscoveryRequest request,
            DiscoveryStore store,
            long runId,
            String runKey,
            List<LabelledObservation> derivation,
            List<OpportunityScore> scores,
            List<OpportunityZone> zones,
            Instant derivationFrom,
            Instant derivationTo,
            Map<String, RegisteredCandidate> registered) {
        for (CandidateRule rule : ruleMining.mine(scores, zones)) {
            if (!passesDiscoveryGate(rule, derivation)) {
                continue;
            }
            List<ExitPolicy> policies = exitPolicyGenerator.generate(rule, derivation);
            if (policies.isEmpty()) {
                continue;
            }
            ExitPolicy policy = policies.getFirst();
            String candidateId = frozenId(runKey, rule, policy);
            if (registered.containsKey(candidateId)) {
                continue;
            }
            FrozenCandidate frozen = new FrozenCandidate(
                    candidateId, rule, policy, derivationFrom, derivationTo,
                    FEATURE_SCHEMA_VERSION, OpportunityScorerV1.SCORE_VERSION);
            long databaseId = store.registerCandidate(runId, rule, derivationFrom, derivationTo);
            store.saveFrozenCandidate(databaseId, frozen);
            registered.put(candidateId, new RegisteredCandidate(databaseId, frozen));
        }
    }

    /**
     * Pre-exit discovery gate: positive oracle net and at least half of calendar months having
     * five or more matching occurrences must be profitable.
     */
    private static boolean passesDiscoveryGate(
            CandidateRule rule, List<LabelledObservation> derivation) {
        Map<YearMonth, List<LabelledObservation>> byMonth = new TreeMap<>();
        derivation.stream().filter(observation -> rule.matches(observation.state())).forEach(observation ->
                byMonth.computeIfAbsent(
                        YearMonth.from(observation.state().id().signalTime().atZone(ZoneOffset.UTC)),
                        ignored -> new ArrayList<>()).add(observation));
        double total = byMonth.values().stream().flatMap(List::stream)
                .mapToDouble(DiscoveryPipeline::oracleNetAtr).sum();
        List<List<LabelledObservation>> sampled = byMonth.values().stream()
                .filter(month -> month.size() >= 5).toList();
        long profitable = sampled.stream()
                .filter(month -> month.stream().mapToDouble(DiscoveryPipeline::oracleNetAtr).sum() > 0).count();
        return total > 0 && !sampled.isEmpty() && profitable * 2 >= sampled.size();
    }

    private static double oracleNetAtr(LabelledObservation observation) {
        return observation.label().mfeAtr();
    }

    private String selectPromotionCandidate(
            DiscoveryStore store,
            long runId,
            Map<String, RegisteredCandidate> registered) {
        List<DiscoveryStore.StoredCandidate> passing = registered.keySet().stream()
                .map(id -> store.findFrozenCandidate(id).orElseThrow())
                .filter(stored -> promotionGate.evaluate(stored.developmentSummary()).passed())
                .sorted(Comparator.comparingDouble(
                                (DiscoveryStore.StoredCandidate stored) ->
                                        stored.developmentSummary().totalNet()).reversed()
                        .thenComparing(stored -> stored.candidate().id()))
                .toList();
        if (passing.isEmpty()) {
            return null;
        }
        String selected = passing.getFirst().candidate().id();
        store.appendExperimentEvent(runId, "promotion_decision",
                Map.of("candidate_id", selected, "passed", true));
        return selected;
    }

    private DiscoveryReport report(
            DiscoveryRequest request,
            DiscoveryRun run,
            Map<String, RegisteredCandidate> registered,
            List<OpportunityScore> scores,
            List<OpportunityZone> zones,
            List<ValidationTrade> trades,
            List<MonthlyResultRow> monthlyRows,
            String promotedCandidate) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("run_key", run.runKey());
        metadata.put("instrument", run.instrument());
        metadata.put("timeframe_minutes", run.timeframeMinutes());
        metadata.put("window_from", run.windowFrom().toString());
        metadata.put("window_to", run.windowTo().toString());
        metadata.put("score_version", run.scoreVersion());
        metadata.put("promoted_candidate", promotedCandidate == null ? "" : promotedCandidate);

        List<Map<String, Object>> patterns = registered.values().stream()
                .sorted(Comparator.comparing(candidate -> candidate.frozen().id()))
                .map(candidate -> pattern(candidate.frozen())).toList();
        List<Map<String, Object>> months = monthlyRows.stream()
                .sorted(Comparator.comparing((MonthlyResultRow row) -> row.result().month())
                        .thenComparing(MonthlyResultRow::candidateId))
                .map(row -> Map.<String, Object>of(
                        "candidate_id", row.candidateId(),
                        "month", row.result().month().toString(),
                        "trade_count", row.result().tradeCount(),
                        "sampled", row.result().sampled(),
                        "net_pnl", row.result().netPnl()))
                .toList();
        Map<String, Map<String, Object>> directions = new LinkedHashMap<>();
        for (Direction direction : Direction.values()) {
            double net = trades.stream().filter(trade -> trade.direction() == direction)
                    .mapToDouble(ValidationTrade::netPnl).sum();
            long count = trades.stream().filter(trade -> trade.direction() == direction).count();
            long zoneCount = zones.stream().filter(zone -> zone.direction() == direction).count();
            directions.put(direction.name(), Map.of(
                    "trade_count", count, "total_net", net, "independent_zones", zoneCount));
        }
        return new DiscoveryReport(
                metadata, patterns, months, directions,
                examples(request, scores, registered.values().stream()
                        .map(RegisteredCandidate::frozen).toList(), trades));
    }

    private static Map<String, Object> pattern(FrozenCandidate candidate) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", candidate.id());
        result.put("rule_id", candidate.rule().id());
        result.put("direction", candidate.rule().direction().name());
        result.put("clauses", candidate.rule().clauses());
        result.put("independent_zones", candidate.rule().independentZones());
        result.put("derivation_from", candidate.derivationFrom().toString());
        result.put("derivation_to", candidate.derivationTo().toString());
        result.put("exit_policy", candidate.exitPolicy());
        return result;
    }

    private static List<DiscoveryReport.Example> examples(
            DiscoveryRequest request,
            List<OpportunityScore> scores,
            List<FrozenCandidate> candidates,
            List<ValidationTrade> trades) {
        if (scores.isEmpty()) {
            return List.of();
        }
        Comparator<OpportunityScore> order = Comparator.comparingDouble(OpportunityScore::compositeScore)
                .thenComparing(score -> score.labelledObservation().state().id().signalTime())
                .thenComparing(score -> score.labelledObservation().state().id().direction());
        List<OpportunityScore> ordered = scores.stream().sorted(order).toList();
        List<Selection> selected = new ArrayList<>();
        selected.add(new Selection("best", ordered.getLast()));
        selected.add(new Selection("median", ordered.get((ordered.size() - 1) / 2)));
        selected.add(new Selection("worst", ordered.getFirst()));
        ordered.stream()
                .filter(score -> score.opportunityClass() != OpportunityClass.RUN)
                .filter(score -> candidates.stream().anyMatch(
                        candidate -> candidate.rule().matches(score.labelledObservation().state())))
                .findFirst()
                .ifPresent(score -> selected.add(new Selection("false-positive", score)));
        ordered.stream()
                .filter(score -> score.opportunityClass() == OpportunityClass.RUN)
                .filter(score -> candidates.stream().noneMatch(
                        candidate -> candidate.rule().matches(score.labelledObservation().state())))
                .findFirst()
                .ifPresent(score -> selected.add(new Selection("missed-run", score)));
        return selected.stream().map(selection -> example(request, selection, candidates, trades)).toList();
    }

    private static DiscoveryReport.Example example(
            DiscoveryRequest request,
            Selection selection,
            List<FrozenCandidate> candidates,
            List<ValidationTrade> trades) {
        var labelled = selection.score().labelledObservation();
        ObservableState state = labelled.state();
        FrozenCandidate candidate = candidates.stream()
                .filter(value -> value.rule().matches(state))
                .sorted(Comparator.comparing(FrozenCandidate::id)).findFirst().orElse(null);
        ValidationTrade trade = trades.stream()
                .filter(value -> value.direction() == state.id().direction()
                        && value.entryIndex() == state.entryIndex())
                .sorted(Comparator.comparing(ValidationTrade::candidateId)).findFirst().orElse(null);
        Map<String, Double> transitions = new TreeMap<>();
        state.features().values().forEach((name, value) -> {
            if (name.startsWith("pillar.")
                    && (name.endsWith(".activated") || name.endsWith(".deactivated"))) {
                transitions.put(name, value);
            }
        });
        Map<String, Object> oracle = new LinkedHashMap<>();
        oracle.put("status", labelled.label().status().name());
        oracle.put("mfe_atr", labelled.label().mfeAtr());
        oracle.put("mae_atr", labelled.label().maeAtr());
        oracle.put("net_240m_atr", oracleNetAtr(labelled));
        Map<String, Object> executable = new LinkedHashMap<>();
        if (trade != null) {
            executable.put("candidate_id", trade.candidateId());
            executable.put("net_pnl", trade.netPnl());
            executable.put("exit_reason", trade.exitReason().name());
            executable.put("capture_ratio", trade.captureRatio());
        }
        List<Map<String, Object>> clauses = candidate == null ? List.of()
                : candidate.rule().clauses().stream().map(clause -> Map.<String, Object>of(
                        "feature", clause.feature(), "operator", clause.operator().name(),
                        "threshold", clause.threshold())).toList();
        return new DiscoveryReport.Example(
                selection.kind(), state.features().values(), transitions, oracle, executable,
                clauses, chartWindow(request, state.signalIndex()));
    }

    private static List<Map<String, Object>> chartWindow(DiscoveryRequest request, int signalIndex) {
        int first = Math.max(request.market().mid().getBeginIndex(), signalIndex - 10);
        int last = Math.min(request.market().mid().getEndIndex(), signalIndex + 10);
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = first; index <= last; index++) {
            var bar = request.market().mid().getBar(index);
            result.add(Map.of(
                    "time", bar.getEndTime().toString(),
                    "open", bar.getOpenPrice().doubleValue(),
                    "high", bar.getHighPrice().doubleValue(),
                    "low", bar.getLowPrice().doubleValue(),
                    "close", bar.getClosePrice().doubleValue()));
        }
        return List.copyOf(result);
    }

    private static List<YearMonth> completeMonths(Instant from, Instant to) {
        YearMonth first = YearMonth.from(from.atZone(ZoneOffset.UTC));
        YearMonth last = YearMonth.from(to.minusNanos(1).atZone(ZoneOffset.UTC));
        List<YearMonth> result = new ArrayList<>();
        for (YearMonth month = first; !month.isAfter(last); month = month.plusMonths(1)) {
            Instant monthFrom = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant monthTo = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            if (!monthFrom.isBefore(from) && !monthTo.isAfter(to)) {
                result.add(month);
            }
        }
        return List.copyOf(result);
    }

    static List<WalkForwardFold> walkForwardFolds(Instant from, Instant to) {
        List<YearMonth> months = completeMonths(from, to);
        if (months.size() < 4) {
            return List.of();
        }
        Instant derivationFrom = months.getFirst().atDay(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        List<WalkForwardFold> folds = new ArrayList<>();
        for (int monthIndex = 3; monthIndex < months.size(); monthIndex++) {
            YearMonth evaluationMonth = months.get(monthIndex);
            folds.add(new WalkForwardFold(
                    derivationFrom,
                    evaluationMonth.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                    evaluationMonth));
        }
        return List.copyOf(folds);
    }

    static String frozenId(String runKey, CandidateRule rule, ExitPolicy policy) {
        String definition = rule.canonicalJson() + "|" + policy.tiers() + "|" + policy.stop()
                + "|" + policy.ratchet() + "|" + policy.invalidationClauses() + "|" + policy.maxHoldingBars();
        return "candidate-" + sha256(runKey + "|" + definition);
    }

    private static String configHash(DiscoveryRequest request) {
        try {
            return sha256(new ObjectMapper().writeValueAsString(request.strategyConfig()));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not hash discovery strategy config", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not create deterministic discovery identity", exception);
        }
    }

    private record RegisteredCandidate(long databaseId, FrozenCandidate frozen) {
    }

    private record MonthlyResultRow(String candidateId, MonthlyResult result) {
    }

    record WalkForwardFold(Instant derivationFrom, Instant derivationTo, YearMonth evaluationMonth) {
        WalkForwardFold {
            Objects.requireNonNull(derivationFrom, "derivationFrom");
            Objects.requireNonNull(derivationTo, "derivationTo");
            Objects.requireNonNull(evaluationMonth, "evaluationMonth");
            if (!derivationFrom.isBefore(derivationTo)) {
                throw new IllegalArgumentException("walk-forward derivation window must be non-empty");
            }
        }
    }

    private record Selection(String kind, OpportunityScore score) {
    }

    @FunctionalInterface
    interface RuleMining {
        List<CandidateRule> mine(List<OpportunityScore> scores, List<OpportunityZone> zones);
    }
}
