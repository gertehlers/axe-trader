package io.g3tech.axetrader.backtest.discovery.validation;

import io.g3tech.axetrader.backtest.discovery.exit.ExitEvaluation;
import io.g3tech.axetrader.backtest.discovery.exit.ExitPolicyEvaluator;
import io.g3tech.axetrader.backtest.discovery.model.ObservableState;
import io.g3tech.axetrader.backtest.series.MarketSeries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Runs one frozen candidate chronologically with at most one open position per instrument. */
public final class ExecutableValidator {

    private final ExitPolicyEvaluator exitPolicyEvaluator;

    public ExecutableValidator() {
        this(new ExitPolicyEvaluator());
    }

    public ExecutableValidator(ExitPolicyEvaluator exitPolicyEvaluator) {
        this.exitPolicyEvaluator = Objects.requireNonNull(exitPolicyEvaluator, "exitPolicyEvaluator");
    }

    public List<ValidationTrade> run(FrozenCandidate candidate, MarketSeries market, List<ObservableState> states) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(market, "market");
        List<ObservableState> chronological = List.copyOf(Objects.requireNonNull(states, "states")).stream()
                .sorted(Comparator.comparing(state -> state.id().signalTime()))
                .toList();
        List<ValidationTrade> trades = new ArrayList<>();
        int finalExitIndex = -1;
        for (ObservableState state : chronological) {
            if (!candidate.rule().matches(state) || state.signalIndex() <= finalExitIndex
                    || state.entryIndex() > market.mid().getEndIndex()) {
                continue;
            }
            ExitEvaluation exit = exitPolicyEvaluator.evaluate(state, market, candidate.exitPolicy(), chronological);
            trades.add(new ValidationTrade(candidate.id(), state.id().direction(), exit.entryIndex(), exit.exitIndex(),
                    market.mid().getBar(exit.entryIndex()).getEndTime(), market.mid().getBar(exit.exitIndex()).getEndTime(),
                    exit.entryPrice(), exit.exitPrice(), exit.netPnl(), exit.exitReason(), exit.tierFills(), exit.captureRatio()));
            finalExitIndex = exit.exitIndex();
        }
        return List.copyOf(trades);
    }
}
