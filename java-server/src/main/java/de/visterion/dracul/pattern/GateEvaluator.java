package de.visterion.dracul.pattern;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Pure gate evaluation (spec T3.3 D1). AND semantics over the predicate's conditions.
 *
 * <p>Return contract: {@code Optional.empty()} = at least one condition referenced a null
 * field and no condition was definitively false — the gate is NOT evaluable and must fail
 * open (the caller logs one WARN per gate+signal). A present {@link MatchResult} is a
 * definite verdict. Fail-open applies to negative ops too: {@code ne}/{@code not_in} on a
 * null field are unevaluable, not trivially true.
 */
public final class GateEvaluator {

    public record MatchResult(boolean matched) {}

    private enum Tri { TRUE, FALSE, UNKNOWN }

    private GateEvaluator() {}

    public static Optional<MatchResult> evaluate(GatePredicate predicate, GateSignalView view) {
        boolean anyUnknown = false;
        for (GateCondition c : predicate.conditions()) {
            switch (evaluateCondition(c, view)) {
                case FALSE -> {
                    return Optional.of(new MatchResult(false));
                }
                case UNKNOWN -> anyUnknown = true;
                case TRUE -> { /* keep going */ }
            }
        }
        if (anyUnknown) return Optional.empty();
        return Optional.of(new MatchResult(true));
    }

    private static Tri evaluateCondition(GateCondition c, GateSignalView view) {
        return switch (c.field()) {
            case "mechanism" -> stringCompare(c, view.mechanism(), false);
            case "symbol" -> stringCompare(c, view.symbol(), false);
            case "sector" -> stringCompare(c, view.sector(), true);
            case "confidence" -> numberCompare(c,
                    view.confidence() == null ? null : BigDecimal.valueOf(view.confidence()));
            case "price" -> numberCompare(c, view.price());
            default -> Tri.UNKNOWN; // unknown field: defensively unevaluable
        };
    }

    private static Tri stringCompare(GateCondition c, String actual, boolean ignoreCase) {
        if (actual == null) return Tri.UNKNOWN;
        boolean contained = c.stringValues().stream()
                .anyMatch(v -> ignoreCase ? v.equalsIgnoreCase(actual) : v.equals(actual));
        return switch (c.op()) {
            case "eq", "in" -> contained ? Tri.TRUE : Tri.FALSE;
            case "ne", "not_in" -> contained ? Tri.FALSE : Tri.TRUE;
            default -> Tri.UNKNOWN;
        };
    }

    private static Tri numberCompare(GateCondition c, BigDecimal actual) {
        if (actual == null) return Tri.UNKNOWN;
        int cmp = actual.compareTo(c.numberValue());
        boolean result = switch (c.op()) {
            case "lt" -> cmp < 0;
            case "lte" -> cmp <= 0;
            case "gt" -> cmp > 0;
            case "gte" -> cmp >= 0;
            default -> false;
        };
        return result ? Tri.TRUE : Tri.FALSE;
    }
}
