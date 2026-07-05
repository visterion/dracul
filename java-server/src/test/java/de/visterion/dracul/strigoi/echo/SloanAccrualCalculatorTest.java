package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.ConceptSeries;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SloanAccrualCalculatorTest {

    private static ConceptSeries.Point duration(String start, String end, String value) {
        return new ConceptSeries.Point(LocalDate.parse(start), LocalDate.parse(end), new BigDecimal(value));
    }

    private static ConceptSeries.Point instant(String end, String value) {
        return new ConceptSeries.Point(null, LocalDate.parse(end), new BigDecimal(value));
    }

    private static AgoraFilings filings(ConceptSeries ni, ConceptSeries ocf, ConceptSeries assets) {
        AgoraFilings f = mock(AgoraFilings.class);
        when(f.concept("ACME", "NetIncomeLoss")).thenReturn(ni);
        when(f.concept("ACME", "NetCashProvidedByUsedInOperatingActivities")).thenReturn(ocf);
        when(f.concept("ACME", "Assets")).thenReturn(assets);
        return f;
    }

    @Test void computesRatioFromLatestMatchingAnnualPeriods() {
        var f = filings(
                new ConceptSeries("NetIncomeLoss", List.of(
                        duration("2024-01-01", "2024-12-31", "800"),
                        duration("2025-01-01", "2025-12-31", "1000"))),
                new ConceptSeries("NetCashProvidedByUsedInOperatingActivities", List.of(
                        duration("2025-01-01", "2025-12-31", "700"))),
                new ConceptSeries("Assets", List.of(
                        instant("2024-12-31", "9000"),
                        instant("2025-12-31", "10000"))));
        AccrualMetrics m = new SloanAccrualCalculator(f).accruals("ACME");
        assertThat(m.available()).isTrue();
        assertThat(m.accrualRatio()).isEqualByComparingTo("0.030000"); // (1000-700)/10000
    }

    @Test void unavailableWhenFlowPeriodsMismatch() {
        var f = filings(
                new ConceptSeries("NetIncomeLoss", List.of(duration("2025-01-01", "2025-12-31", "1000"))),
                new ConceptSeries("NetCashProvidedByUsedInOperatingActivities",
                        List.of(duration("2024-01-01", "2024-12-31", "700"))),
                new ConceptSeries("Assets", List.of(instant("2025-12-31", "10000"))));
        assertThat(new SloanAccrualCalculator(f).accruals("ACME").available()).isFalse();
    }

    @Test void ignoresNonAnnualDurationsAndDurationAssets() {
        var f = filings(
                new ConceptSeries("NetIncomeLoss", List.of(duration("2025-10-01", "2025-12-31", "250"))), // ~90d
                new ConceptSeries("NetCashProvidedByUsedInOperatingActivities",
                        List.of(duration("2025-01-01", "2025-12-31", "700"))),
                new ConceptSeries("Assets", List.of(duration("2025-01-01", "2025-12-31", "10000")))); // not instant
        assertThat(new SloanAccrualCalculator(f).accruals("ACME").available()).isFalse();
    }

    @Test void unavailableOnEmptySeriesOrZeroAssets() {
        var empty = filings(ConceptSeries.empty("NetIncomeLoss"),
                ConceptSeries.empty("NetCashProvidedByUsedInOperatingActivities"),
                ConceptSeries.empty("Assets"));
        assertThat(new SloanAccrualCalculator(empty).accruals("ACME").available()).isFalse();

        var zero = filings(
                new ConceptSeries("NetIncomeLoss", List.of(duration("2025-01-01", "2025-12-31", "1000"))),
                new ConceptSeries("NetCashProvidedByUsedInOperatingActivities",
                        List.of(duration("2025-01-01", "2025-12-31", "700"))),
                new ConceptSeries("Assets", List.of(instant("2025-12-31", "0"))));
        assertThat(new SloanAccrualCalculator(zero).accruals("ACME").available()).isFalse();
    }
}
