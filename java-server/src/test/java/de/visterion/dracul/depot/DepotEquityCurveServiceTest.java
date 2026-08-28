package de.visterion.dracul.depot;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DepotEquityCurveServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-01-08T10:00:00Z"), ZoneOffset.UTC);

    private DepotEquitySnapshot daily(String isoDay, String equity) {
        return daily(isoDay, equity, "EUR");
    }

    private DepotEquitySnapshot daily(String isoDay, String equity, String currency) {
        return new DepotEquitySnapshot(1L, "conn-1", Instant.parse(isoDay + "T00:00:00Z"),
                "DAILY", new BigDecimal(equity), new BigDecimal("10.00"),
                new BigDecimal(equity).subtract(new BigDecimal("10.00")),
                currency, BigDecimal.ZERO, "MEASURED");
    }

    private DepotEquitySnapshot intraday(String iso, String equity) {
        return new DepotEquitySnapshot(1L, "conn-1", Instant.parse(iso),
                "INTRADAY", new BigDecimal(equity), new BigDecimal("10.00"),
                new BigDecimal(equity).subtract(new BigDecimal("10.00")),
                "EUR", BigDecimal.ZERO, "MEASURED");
    }

    // Package-private Clock overload, same pattern as DepotEquitySnapshotJob.
    private DepotEquityCurveService service(DepotEquitySnapshotRepository repo) {
        return new DepotEquityCurveService(repo, CLOCK);
    }

    @Test
    void unknownRangeIs400() {
        var repo = mock(DepotEquitySnapshotRepository.class);

        assertThatThrownBy(() -> service(repo).curve("conn-1", "bogus"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid range");
    }

    // RANGE_DAYS is a Map.of(...); containsKey(null) throws NPE there, not a clean 400. Today
    // the controller's @RequestParam is required so null never reaches this method -- but that
    // is a fact about a different class, not a contract this service should depend on silently.
    @Test
    void nullRangeIs400() {
        var repo = mock(DepotEquitySnapshotRepository.class);

        assertThatThrownBy(() -> service(repo).curve("conn-1", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid range");
    }

    @Test
    void oneDayReadsIntradayRowsAndReportsThatGranularity() {
        var repo = mock(DepotEquitySnapshotRepository.class);
        when(repo.series(eq("conn-1"), eq("INTRADAY"), any()))
                .thenReturn(List.of(intraday("2026-01-08T13:05:00Z", "100.00"),
                                    intraday("2026-01-08T13:20:00Z", "110.00")));

        var curve = service(repo).curve("conn-1", "1d");

        assertThat(curve.granularity()).isEqualTo("INTRADAY");
        assertThat(curve.points()).extracting(DepotEquityCurveService.CurvePoint::t)
                .containsExactly("2026-01-08T13:05:00Z", "2026-01-08T13:20:00Z");
    }

    @Test
    void weekReadsDailyRowsAndFormatsTAsAPlainDate() {
        var repo = mock(DepotEquitySnapshotRepository.class);
        when(repo.series(eq("conn-1"), eq("DAILY"), any()))
                .thenReturn(List.of(daily("2026-01-05", "100.00"), daily("2026-01-06", "110.00")));

        var curve = service(repo).curve("conn-1", "1w");

        assertThat(curve.granularity()).isEqualTo("DAILY");
        assertThat(curve.points()).extracting(DepotEquityCurveService.CurvePoint::t)
                .containsExactly("2026-01-05", "2026-01-06");
    }

    @Test
    void weekWindowStartsAtAMidnightBoundarySoTheOldestDayIsIncluded() {
        var repo = mock(DepotEquitySnapshotRepository.class);
        when(repo.series(any(), any(), any())).thenReturn(List.of());

        service(repo).curve("conn-1", "1w");

        // now = 2026-01-08T10:00:00Z -> floor to the day, then minus 7 days.
        verify(repo).series("conn-1", "DAILY", Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void oneDayWindowStartsAtAMidnightBoundarySoTheWholeDayIsIncluded() {
        var repo = mock(DepotEquitySnapshotRepository.class);
        when(repo.series(any(), any(), any())).thenReturn(List.of());

        service(repo).curve("conn-1", "1d");

        // now = 2026-01-08T10:00:00Z -> floor to the day, not the raw instant.
        verify(repo).series("conn-1", "INTRADAY", Instant.parse("2026-01-08T00:00:00Z"));
    }

    @Test
    void maxRangeAsksForTheWholeSeries() {
        var repo = mock(DepotEquitySnapshotRepository.class);
        when(repo.series(any(), any(), any())).thenReturn(List.of());

        service(repo).curve("conn-1", "max");

        verify(repo).series("conn-1", "DAILY", Instant.EPOCH);
    }

    @Test
    void relativeIsPercentAgainstTheFirstReturnedPoint() {
        var repo = mock(DepotEquitySnapshotRepository.class);
        when(repo.series(any(), any(), any()))
                .thenReturn(List.of(daily("2026-01-05", "100.00"), daily("2026-01-06", "110.00")));

        var curve = service(repo).curve("conn-1", "1w");

        assertThat(curve.relative()).extracting(DepotEquityCurveService.RelativePoint::pct)
                .containsExactly(new BigDecimal("0.00"), new BigDecimal("10.00"));
    }

    @Test
    void relativeIsNullWithFewerThanTwoPoints() {
        var repo = mock(DepotEquitySnapshotRepository.class);
        when(repo.series(any(), any(), any())).thenReturn(List.of(daily("2026-01-05", "100.00")));

        assertThat(service(repo).curve("conn-1", "1w").relative()).isNull();
    }

    @Test
    void firstZeroYieldsAFlatZeroPercentSeriesInsteadOfDividingByZero() {
        var repo = mock(DepotEquitySnapshotRepository.class);
        when(repo.series(any(), any(), any()))
                .thenReturn(List.of(daily("2026-01-05", "0.00"), daily("2026-01-06", "10.00")));

        assertThat(service(repo).curve("conn-1", "1w").relative())
                .extracting(DepotEquityCurveService.RelativePoint::pct)
                .containsExactly(new BigDecimal("0.00"), new BigDecimal("0.00"));
    }

    @Test
    void emptySeriesYieldsNoPointsNoRelativeAndNoCurrency() {
        var repo = mock(DepotEquitySnapshotRepository.class);
        when(repo.series(any(), any(), any())).thenReturn(List.of());

        var curve = service(repo).curve("conn-1", "1m");

        assertThat(curve.points()).isEmpty();
        assertThat(curve.relative()).isNull();
        assertThat(curve.currency()).isNull();
        assertThat(curve.granularity()).isEqualTo("DAILY");
    }

    @Test
    void currencyComesFromTheNewestRow() {
        var repo = mock(DepotEquitySnapshotRepository.class);
        when(repo.series(any(), any(), any()))
                .thenReturn(List.of(daily("2026-01-05", "100.00", "EUR"),
                                    daily("2026-01-06", "110.00", "USD")));

        assertThat(service(repo).curve("conn-1", "1w").currency()).isEqualTo("USD");
    }

    @Test
    void aMissingDayStaysAGapAndIsNotFilled() {
        var repo = mock(DepotEquitySnapshotRepository.class);
        when(repo.series(any(), any(), any()))
                .thenReturn(List.of(daily("2026-01-05", "100.00"), daily("2026-01-07", "120.00")));

        assertThat(service(repo).curve("conn-1", "1w").points())
                .extracting(DepotEquityCurveService.CurvePoint::t)
                .containsExactly("2026-01-05", "2026-01-07");
    }

    @Test
    void sourceIsCarriedThroughPerPoint() {
        var repo = mock(DepotEquitySnapshotRepository.class);
        when(repo.series(any(), any(), any())).thenReturn(List.of(daily("2026-01-05", "100.00")));

        assertThat(service(repo).curve("conn-1", "1w").points())
                .singleElement()
                .extracting(DepotEquityCurveService.CurvePoint::source)
                .isEqualTo("MEASURED");
    }
}
