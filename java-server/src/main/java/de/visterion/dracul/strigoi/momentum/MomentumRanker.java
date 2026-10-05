package de.visterion.dracul.strigoi.momentum;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 12-1 momentum ranking, pure (spec 2026-10-04 §5.2). Momentum = roc(lookback − skip) at t − skip
 * = {@code rocLong[size − 1 − skip]} (percent) = close(t−21)/close(t−252) − 1. A symbol is
 * ranked only with that value present (⇔ ≥ 252 completed bars), a last close ≥ min-price and no
 * suspect gap in its roc1 series (≤ −gap or ≥ +upGap: un-adjusted spin-off / reverse split —
 * {@code data_suspect}: never a new entry, never used to exit a held name). A day at or below
 * −15 % that is not suspect marks {@code possible_corporate_action} (information only). Order:
 * momentum descending, then symbol ascending.
 */
public final class MomentumRanker {

    public static final String MISSING = "missing";
    public static final String TOO_FEW_BARS = "too_few_bars";
    public static final String BELOW_MIN_PRICE = "below_min_price";
    public static final String DATA_SUSPECT = "data_suspect";

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private MomentumRanker() {}

    public record Ranked(int rank, String symbol, BigDecimal momentumPct, BigDecimal lastClose,
            BigDecimal return1mPct, BigDecimal worst1dPct, boolean possibleCorporateAction) {}

    public record Suspect(String symbol, BigDecimal worst1dPct, BigDecimal best1dPct) {}

    public record Ranking(List<Ranked> ranked, Map<String, String> unranked, List<Suspect> suspects,
            LocalDate asOf, int universeSize) {

        public Optional<Ranked> find(String symbol) {
            return ranked.stream().filter(r -> r.symbol().equalsIgnoreCase(symbol)).findFirst();
        }

        public long count(String reason) {
            return unranked.values().stream().filter(reason::equals).count();
        }
    }

    private record Candidate(String symbol, BigDecimal momentum, BigDecimal close, BigDecimal ret1m,
            BigDecimal worst, boolean medium) {}

    public static Ranking rank(List<String> universe, Map<String, MomentumBarsClient.Series> data,
            MomentumSettings s) {
        BigDecimal downGap = s.gapSuspectPct().multiply(HUNDRED).negate();
        BigDecimal upGap = s.upGapPct().multiply(HUNDRED);
        BigDecimal mediumGap = MomentumSettings.MEDIUM_GAP_PCT.multiply(HUNDRED).negate();
        List<Candidate> candidates = new ArrayList<>();
        Map<String, String> unranked = new LinkedHashMap<>();
        List<Suspect> suspects = new ArrayList<>();
        LocalDate asOf = null;
        for (String symbol : universe) {
            MomentumBarsClient.Series d = data.get(symbol);
            if (d == null || d.status() == MomentumBarsClient.Status.NO_DATA) {
                unranked.put(symbol, MISSING);
                continue;
            }
            if (d.asOf() != null && (asOf == null || d.asOf().isAfter(asOf))) asOf = d.asOf();
            int idx = d.rocLong().size() - 1 - s.skipDays();
            if (d.status() == MomentumBarsClient.Status.INSUFFICIENT || idx < 0
                    || d.rocLong().get(idx) == null) {
                unranked.put(symbol, TOO_FEW_BARS);
                continue;
            }
            if (d.lastClose().compareTo(s.minPrice()) < 0) {
                unranked.put(symbol, BELOW_MIN_PRICE);
                continue;
            }
            BigDecimal worst = null;
            BigDecimal best = null;
            for (BigDecimal r : d.roc1()) {
                if (r == null) continue;
                if (worst == null || r.compareTo(worst) < 0) worst = r;
                if (best == null || r.compareTo(best) > 0) best = r;
            }
            if ((worst != null && worst.compareTo(downGap) <= 0)
                    || (best != null && best.compareTo(upGap) >= 0)) {
                unranked.put(symbol, DATA_SUSPECT);
                suspects.add(new Suspect(symbol, worst, best));
                continue;
            }
            candidates.add(new Candidate(symbol, d.rocLong().get(idx), d.lastClose(),
                    oneMonth(d.roc1(), s.skipDays()), worst,
                    worst != null && worst.compareTo(mediumGap) <= 0));
        }
        candidates.sort(Comparator.comparing(Candidate::momentum, Comparator.reverseOrder())
                .thenComparing(Candidate::symbol));
        List<Ranked> ranked = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            ranked.add(new Ranked(i + 1, c.symbol(), c.momentum(), c.close(), c.ret1m(), c.worst(),
                    c.medium()));
        }
        return new Ranking(List.copyOf(ranked), Collections.unmodifiableMap(unranked),
                List.copyOf(suspects), asOf, universe.size());
    }

    /** Π(1 + r/100) over the last {@code days} daily returns − 1, percent, 2 decimals; null when
     *  one of them is missing. */
    static BigDecimal oneMonth(List<BigDecimal> roc1, int days) {
        if (roc1.size() < days) return null;
        BigDecimal growth = BigDecimal.ONE;
        for (BigDecimal r : roc1.subList(roc1.size() - days, roc1.size())) {
            if (r == null) return null;
            growth = growth.multiply(BigDecimal.ONE.add(r.divide(HUNDRED, MathContext.DECIMAL64)),
                    MathContext.DECIMAL64);
        }
        return growth.subtract(BigDecimal.ONE).multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);
    }
}
