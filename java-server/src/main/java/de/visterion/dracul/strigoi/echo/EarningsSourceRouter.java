package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.hunting.DataSourceResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/** Selects the configured PeadEarningsSource; on unavailable health, tries the others. */
@Component
public class EarningsSourceRouter {

    private final List<PeadEarningsSource> sources;
    private final String preferred;

    public EarningsSourceRouter(
            List<PeadEarningsSource> sources,
            @Value("${dracul.strigoi.echo.earnings-source:finnhub}") String preferred) {
        this.sources = sources;
        this.preferred = preferred;
    }

    public DataSourceResult<EarningsObservation> recent(LocalDate from, LocalDate to) {
        PeadEarningsSource primary = sources.stream()
                .filter(s -> s.id().equals(preferred)).findFirst()
                .orElse(sources.isEmpty() ? null : sources.get(0));
        if (primary == null) return DataSourceResult.unavailable("none", "no earnings source configured");

        DataSourceResult<EarningsObservation> result = primary.recent(from, to);
        if (result.health().isHealthy()) return result;

        for (PeadEarningsSource s : sources) {
            if (s == primary) continue;
            DataSourceResult<EarningsObservation> alt = s.recent(from, to);
            if (alt.health().isHealthy()) return alt;
        }
        return result; // all unavailable -> return primary's unavailable result
    }
}
