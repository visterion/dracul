package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.hunting.agora.ConceptSeries;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EpsHistoryShaperTest {

    private static ConceptSeries.Point p(String start, String end, String value) {
        return new ConceptSeries.Point(
                start == null ? null : LocalDate.parse(start), LocalDate.parse(end), new BigDecimal(value));
    }

    @Test void keepsOnlyQuarterlyDurationsNewestFirst() {
        var series = new ConceptSeries("eps", List.of(
                p("2025-01-01", "2025-12-31", "8.00"),   // annual -> dropped
                p("2025-10-01", "2025-12-31", "2.10"),   // ~91d -> kept
                p("2026-01-01", "2026-03-31", "2.40"),   // ~89d -> kept
                p(null, "2026-03-31", "9.99")));          // no start -> dropped
        List<QuarterlyEps> out = new EpsHistoryShaper().quarterly(series, 16);
        assertThat(out).hasSize(2);
        assertThat(out.get(0).periodEnd()).isEqualTo(LocalDate.parse("2026-03-31")); // newest first
        assertThat(out.get(0).eps()).isEqualByComparingTo("2.40");
        assertThat(out.get(1).eps()).isEqualByComparingTo("2.10");
    }

    @Test void capsAtMaxQuarters() {
        var series = new ConceptSeries("eps", List.of(
                p("2025-04-01", "2025-06-30", "1.0"),
                p("2025-07-01", "2025-09-30", "1.1"),
                p("2025-10-01", "2025-12-31", "1.2")));
        assertThat(new EpsHistoryShaper().quarterly(series, 2)).hasSize(2);
    }

    @Test void emptySeriesYieldsEmptyList() {
        assertThat(new EpsHistoryShaper().quarterly(ConceptSeries.empty("eps"), 16)).isEmpty();
    }
}
