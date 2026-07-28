package io.g3tech.axetrader.backtest.discovery.analysis;

import io.g3tech.axetrader.backtest.discovery.model.ObservableState;
import io.g3tech.axetrader.backtest.runner.Direction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable compact, backward-only entry rule, with evidence counted by opportunity zone. */
public record CandidateRule(
        String id,
        Direction direction,
        List<RuleClause> clauses,
        int independentZones,
        double meanNetMfeAtr,
        double meanScore) {

    private static final Comparator<RuleClause> CLAUSE_ORDER = Comparator
            .comparing(RuleClause::feature)
            .thenComparing(RuleClause::operator)
            .thenComparingDouble(RuleClause::threshold);

    public CandidateRule {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(direction, "direction");
        clauses = List.copyOf(Objects.requireNonNull(clauses, "clauses")).stream().sorted(CLAUSE_ORDER).toList();
        if (id.isBlank() || clauses.isEmpty() || clauses.size() > 4 || independentZones < 0
                || !Double.isFinite(meanNetMfeAtr) || !Double.isFinite(meanScore)) {
            throw new IllegalArgumentException("invalid candidate rule");
        }
        Set<RuleClause> distinctClauses = new HashSet<>(clauses);
        if (distinctClauses.size() != clauses.size()) {
            throw new IllegalArgumentException("candidate rule clauses must be distinct");
        }
    }

    public static CandidateRule create(
            Direction direction,
            List<RuleClause> clauses,
            int independentZones,
            double meanNetMfeAtr,
            double meanScore) {
        List<RuleClause> canonicalClauses = canonicalClauses(clauses);
        return new CandidateRule(
                sha256(canonicalJson(direction, canonicalClauses)),
                direction,
                canonicalClauses,
                independentZones,
                meanNetMfeAtr,
                meanScore);
    }

    public boolean matches(ObservableState state) {
        Objects.requireNonNull(state, "state");
        return state.id().direction() == direction && clauses.stream().allMatch(clause -> clause.matches(state.features()));
    }

    /** Stable JSON identity persisted before a later chronological evaluation fold reads a candidate. */
    public String canonicalJson() {
        return canonicalJson(direction, clauses);
    }

    private static List<RuleClause> canonicalClauses(List<RuleClause> clauses) {
        return List.copyOf(Objects.requireNonNull(clauses, "clauses")).stream().sorted(CLAUSE_ORDER).toList();
    }

    private static String canonicalJson(Direction direction, List<RuleClause> clauses) {
        Objects.requireNonNull(direction, "direction");
        StringBuilder json = new StringBuilder("{\"direction\":\"")
                .append(direction.name())
                .append("\",\"clauses\":[");
        for (int index = 0; index < clauses.size(); index++) {
            RuleClause clause = clauses.get(index);
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"feature\":\"").append(escapeJson(clause.feature()))
                    .append("\",\"operator\":\"").append(clause.operator().name())
                    .append("\",\"threshold\":").append(Double.toString(clause.threshold()))
                    .append('}');
        }
        return json.append("]}").toString();
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
