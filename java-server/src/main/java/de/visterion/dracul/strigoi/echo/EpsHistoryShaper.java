package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.hunting.agora.ConceptSeries;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shapes a raw EPS concept series into quarterly rows: keeps only ~quarterly (80-100d)
 * durations, dedupes by period end, sorts newest-first, caps at maxQuarters. Relocated from
 * the deleted EdgarEpsHistory adapter — the fetch (and the Diluted→Basic tag fallback) is
 * Agora's get_eps_history; the quarter-filtering is Dracul's.
 */
@Component
public class EpsHistoryShaper {

    private static final long MIN_QUARTER_DAYS = 80;
    private static final long MAX_QUARTER_DAYS = 100;

    public List<QuarterlyEps> quarterly(ConceptSeries series, int maxQuarters) {
        Map<LocalDate, BigDecimal> byEnd = new LinkedHashMap<>();
        for (ConceptSeries.Point p : series.points()) {
            if (p.periodStart() == null || p.periodEnd() == null || p.value() == null) continue;
            long days = ChronoUnit.DAYS.between(p.periodStart(), p.periodEnd());
            if (days < MIN_QUARTER_DAYS || days > MAX_QUARTER_DAYS) continue;  // quarterly only
            byEnd.put(p.periodEnd(), p.value());
        }
        List<QuarterlyEps> out = new ArrayList<>();
        byEnd.forEach((end, val) -> out.add(new QuarterlyEps(end, val)));
        out.sort(Comparator.comparing(QuarterlyEps::periodEnd).reversed());
        return out.size() > maxQuarters ? out.subList(0, maxQuarters) : out;
    }
}
