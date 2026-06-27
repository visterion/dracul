package de.visterion.dracul.strigoi.echo;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** Deterministic SP1 enrichment: SUE (+decile across the batch), revenue-surprise, double-beat,
 *  consecutive seasonal beats. Pure orchestration over SueEngine + EpsHistoryPort. */
@Component
public class EchoEnrichmentService {

    private final SueEngine sueEngine;
    private final EpsHistoryPort epsHistory;

    public EchoEnrichmentService(SueEngine sueEngine, EpsHistoryPort epsHistory) {
        this.sueEngine = sueEngine;
        this.epsHistory = epsHistory;
    }

    public List<EnrichedPeadCandidate> enrich(List<PeadCandidate> candidates) {
        record Partial(PeadCandidate c, Sue sue, List<QuarterlyEps> hist) {}
        List<Partial> partials = new ArrayList<>();
        List<Double> sueValues = new ArrayList<>();

        for (PeadCandidate c : candidates) {
            List<QuarterlyEps> hist = epsHistory.quarterlyEps(c.symbol(), 16);
            Sue sue = sueEngine.timeSeriesSue(c.epsActual(), c.reportDate(), hist);
            if (sue.available() && sue.value() != null) sueValues.add(sue.value());
            partials.add(new Partial(c, sue, hist));
        }
        boolean thin = sueValues.size() < 20;

        List<EnrichedPeadCandidate> out = new ArrayList<>();
        for (Partial p : partials) {
            PeadCandidate c = p.c();
            Integer decile = null;
            boolean approximate = p.sue().approximate();
            if (p.sue().available() && p.sue().value() != null) {
                decile = sueEngine.decile(p.sue().value(), sueValues, thin);
                approximate = thin;
            }
            BigDecimal revSurprise = revenueSurprise(c.revenueActual(), c.revenueEstimate());
            boolean doubleBeat = revSurprise != null && revSurprise.signum() > 0;
            Integer consecutive = p.hist().isEmpty() ? null : sueEngine.seasonalBeatStreak(p.hist());

            out.add(new EnrichedPeadCandidate(
                    c.symbol(), c.companyName(), c.reportDate(),
                    ChronoUnit.DAYS.between(c.reportDate(), LocalDate.now()),
                    c.epsActual(), c.epsEstimate(), c.surprisePercent(),
                    p.sue().value(), decile, approximate, p.sue().available(),
                    revSurprise, doubleBeat, consecutive, c.currentPrice(),
                    null, null, false, null, null, null, null, null, null, false));
        }
        return out;
    }

    private static BigDecimal revenueSurprise(BigDecimal actual, BigDecimal estimate) {
        if (actual == null || estimate == null || estimate.signum() == 0) return null;
        return actual.subtract(estimate)
                .divide(estimate.abs(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }
}
