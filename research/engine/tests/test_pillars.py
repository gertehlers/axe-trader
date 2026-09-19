import numpy as np

from conftest import make_bar_frame
from engine.pillars import PillarConfig, compute_pillars, fire_rates


def test_four_pillars_are_present_and_structure_is_absent():
    votes = compute_pillars(make_bar_frame(), PillarConfig())
    assert votes.names == ["RSI+BB", "Candle", "S/R", "Vol+Trend"]


def test_scores_are_the_count_of_voting_pillars():
    votes = compute_pillars(make_bar_frame(), PillarConfig())
    stacked = np.vstack([votes.bullish[name] for name in votes.names]).sum(axis=0)
    assert np.array_equal(votes.bullish_score, stacked)


def test_scores_never_exceed_the_pillar_count():
    votes = compute_pillars(make_bar_frame(), PillarConfig())
    assert votes.bullish_score.max() <= 4
    assert votes.bearish_score.min() >= 0


def test_support_resistance_fires_at_every_new_extreme():
    # ta4j's lowest() includes the current bar, so at a new low the distance is zero and
    # "near support" is trivially true. Replicated deliberately (spec 3.2) and pinned here.
    falling = make_bar_frame()
    close = 200.0 - np.arange(len(falling), dtype=float)
    for column, offset in (("close_bid", -0.01), ("close_ask", 0.01),
                           ("low_bid", -0.61), ("low_ask", -0.59),
                           ("high_bid", 0.59), ("high_ask", 0.61)):
        falling[column] = close + offset
    votes = compute_pillars(falling, PillarConfig())
    assert votes.bullish["S/R"][300:].all()


def test_fire_rates_report_each_pillar_and_the_score_histogram():
    votes = compute_pillars(make_bar_frame(), PillarConfig())
    rates = fire_rates(votes, warmup=250)
    assert set(rates["bullish"]) == set(votes.names)
    assert all(0.0 <= v <= 1.0 for v in rates["bullish"].values())
    assert sum(rates["bullish_score_histogram"].values()) == len(votes.bullish_score) - 250
