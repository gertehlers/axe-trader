package io.g3tech.axetrader.backtest.discovery.exit;

import io.g3tech.axetrader.backtest.discovery.analysis.RuleClause;
import io.g3tech.axetrader.backtest.discovery.model.ObservableState;
import io.g3tech.axetrader.backtest.runner.Direction;
import io.g3tech.axetrader.backtest.runner.ExitReason;
import io.g3tech.axetrader.backtest.runner.TieredExitEngine;
import io.g3tech.axetrader.backtest.series.MarketSeries;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Applies a frozen policy with side-aware prices and post-completion invalidations. */
public final class ExitPolicyEvaluator {

    public ExitEvaluation evaluate(
            ObservableState entry, MarketSeries market, ExitPolicy policy,
            List<ObservableState> chronologicalStates) {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(policy, "policy");
        Direction direction = entry.id().direction();
        if (policy.rule().direction() != direction) {
            throw new IllegalArgumentException("policy direction must match the entry");
        }
        int entryIndex = entry.entryIndex();
        int normalLast = Math.min(market.mid().getEndIndex(), entryIndex + policy.maxHoldingBars());
        int closeIndex = entry.minutesToTradingClose() <= 0 ? entryIndex
                : entryIndex + entry.minutesToTradingClose() / 5;
        int finalIndex = Math.min(normalLast, closeIndex);
        if (finalIndex <= entryIndex) {
            throw new IllegalArgumentException("entry needs an executable post-entry bar");
        }
        boolean tradingClose = closeIndex <= normalLast && closeIndex <= market.mid().getEndIndex();
        Map<Integer, List<RuleClause>> invalidations = invalidations(
                entryIndex, finalIndex, policy.invalidationClauses(), chronologicalStates);
        Set<Integer> invalidationExits = invalidations.keySet().stream()
                .filter(index -> !tradingClose || index < finalIndex)
                .collect(java.util.stream.Collectors.toSet());
        double entryPrice = market.entryPrice(direction, entryIndex);
        List<TieredExitEngine.TierLevel> tiers = policy.tiers().stream()
                .map(tier -> new TieredExitEngine.TierLevel(tier.fraction(), tier.targetAtr() * entry.entryAtr())).toList();
        TieredExitEngine.Outcome outcome = TieredExitEngine.tieredExit(
                market, direction, entryIndex, entryPrice, policy.stop().distanceAtr() * entry.entryAtr(), tiers,
                policy.ratchet(), policy.maxHoldingBars(), finalIndex,
                tradingClose ? ExitReason.TRADING_CLOSE : ExitReason.END, invalidationExits);
        double pnl = direction == Direction.LONG
                ? outcome.weightedPrice() - entryPrice : entryPrice - outcome.weightedPrice();
        OracleExit oracle = OracleExit.best(entry, market, Math.min(policy.maxHoldingBars(), finalIndex - entryIndex));
        double capture = oracle.netPnl() == 0.0 ? 0.0 : pnl / oracle.netPnl();
        List<RuleClause> fired = outcome.finalReason() == ExitReason.INVALIDATION
                ? invalidations.getOrDefault(outcome.index(), List.of()) : List.of();
        return new ExitEvaluation(entryIndex, entryPrice, outcome.index(), outcome.weightedPrice(), pnl,
                outcome.finalReason(), outcome.fills(), capture, fired);
    }

    private static Map<Integer, List<RuleClause>> invalidations(
            int entryIndex, int finalIndex, List<RuleClause> clauses, List<ObservableState> states) {
        Map<Integer, List<RuleClause>> result = new LinkedHashMap<>();
        if (clauses.isEmpty()) {
            return result;
        }
        List<ObservableState> ordered = List.copyOf(Objects.requireNonNull(states, "chronologicalStates")).stream()
                .sorted(Comparator.comparingInt(ObservableState::signalIndex)).toList();
        for (ObservableState state : ordered) {
            if (state.signalIndex() < entryIndex || state.signalIndex() >= finalIndex) {
                continue;
            }
            if (clauses.stream().allMatch(clause -> clause.matches(state.features()))) {
                result.putIfAbsent(state.signalIndex() + 1, clauses);
            }
        }
        return result;
    }
}
