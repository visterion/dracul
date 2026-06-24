package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.hunting.yahoo.EarningsEvent;
import de.visterion.dracul.hunting.yahoo.YahooEarningsAdapter;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/** Fallback PeadEarningsSource backed by the (unofficial) Yahoo earnings calendar. No revenue. */
@Component
public class YahooEarningsSource implements PeadEarningsSource {

    private final YahooEarningsAdapter yahoo;

    public YahooEarningsSource(YahooEarningsAdapter yahoo) { this.yahoo = yahoo; }

    @Override
    public DataSourceResult<EarningsObservation> recent(LocalDate from, LocalDate to) {
        var raw = yahoo.recentEarnings(from, to);
        List<EarningsObservation> mapped = raw.items().stream().map(YahooEarningsSource::map).toList();
        return new DataSourceResult<>(mapped, raw.health());
    }

    private static EarningsObservation map(EarningsEvent e) {
        return new EarningsObservation(e.symbol(), e.companyName(), e.reportDate(),
                e.epsActual(), e.epsEstimate(), e.surprisePercent(), null, null);
    }

    @Override public String id() { return "yahoo"; }
}
