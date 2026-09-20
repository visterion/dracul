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
        return dailyWithFlow(isoDay, equity, currency, "0.00");
    }

    private DepotEquitySnapshot dailyWithFlow(String isoDay, String equity, String flow) {
        return dailyWithFlow(isoDay, equity, "EUR", flow);
    }

    private DepotEquitySnapshot dailyWithFlow(String isoDay, String equity, String currency, String flow) {
        return new DepotEquitySnapshot(1L, "conn-1", Instant.parse(isoDay + "T00:00:00Z"),
                "DAILY", new BigDecimal(equity), new BigDecimal("10.00"),
                new BigDecimal(equity).subtract(new BigDecimal("10.00")),
                currency, new BigDecimal(flow), "MEASURED");
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

    // Time-weighted return: a deposit must not appear as investment gain. Formula (see
    // DepotEquityCurveService.relative): r_i = (E_i - F_i) / E_{i-1} - 1, chain-linked;
    // pct_i = (prod_{k<=i}(1+r_k) - 1) * 100. All values here are invented round numbers,
    // not production data (CLAUDE.md: committed fixtures must be synthetic).
    @Test
    void depositIsExcludedFromTheReturnByNettingItOutOfItsInterval() {
        var repo = mock(DepotEquitySnapshotRepository.class);
        when(repo.series(any(), any(), any())).thenReturn(List.of(
                dailyWithFlow("2026-01-01", "1000.00", "0.00"),
                dailyWithFlow("2026-01-02", "1100.00", "0.00"),
                dailyWithFlow("2026-01-03", "21000.00", "20000.00"),
                dailyWithFlow("2026-01-04", "21210.00", "0.00")));

        var relative = service(repo).curve("conn-1", "1m").relative();

        // r_1 = (1100 - 0) / 1000 - 1 = 0.10 -> pct_1 = 10.00
        // r_2 = (21000 - 20000) / 1100 - 1 = 1000/1100 - 1 = 10/11 - 1 = -0.0909090909
        //       (a -9.09% residual on top of the 20 000 deposit, which is fully netted out)
        //       cumulative = 1.10 * (10/11) = 1.0 exactly -> pct_2 = 0.00
        // r_3 = 21210/21000 - 1 = 0.01
        //       cumulative = 1.0 * 1.01 = 1.01 -> pct_3 = 1.00
        assertThat(relative).extracting(DepotEquityCurveService.RelativePoint::pct)
                .containsExactly(
                        new BigDecimal("0.00"),
                        new BigDecimal("10.00"),
                        new BigDecimal("0.00"),
                        new BigDecimal("1.00"));
    }

    @Test
    void withdrawalIsExcludedFromTheReturnSymmetricallyToADeposit() {
        var repo = mock(DepotEquitySnapshotRepository.class);
        when(repo.series(any(), any(), any())).thenReturn(List.of(
                dailyWithFlow("2026-09-15", "1000.00", "0.00"),
                dailyWithFlow("2026-09-16", "1100.00", "0.00"),
                dailyWithFlow("2026-09-17", "1000.00", "-200.00")));

        var relative = service(repo).curve("conn-1", "1m").relative();

        // r_1 = 1100/1000 - 1 = 0.10 -> pct_1 = 10.00
        // r_2 = (1000 - (-200)) / 1100 - 1 = 1200/1100 - 1 = 2/22 = 0.0909090909
        //       cumulative = 1.10 * 1.0909090909 = 1.2 (11/10 * 12/11 = 12/10) -> pct_2 = 20.00
        assertThat(relative).extracting(DepotEquityCurveService.RelativePoint::pct)
                .containsExactly(
                        new BigDecimal("0.00"),
                        new BigDecimal("10.00"),
                        new BigDecimal("20.00"));
    }

    @Test
    void flowOnTheFirstPointIsIgnoredBecauseThereIsNoPriorInterval() {
        var repo = mock(DepotEquitySnapshotRepository.class);
        when(repo.series(any(), any(), any())).thenReturn(List.of(
                dailyWithFlow("2026-09-15", "1000.00", "500.00"),
                dailyWithFlow("2026-09-16", "1100.00", "0.00")));

        var relative = service(repo).curve("conn-1", "1m").relative();

        // F_0 = 500 is never read: the loop starts at i = 1 and only ever looks at F_i for
        // i >= 1. r_1 = (1100 - 0) / 1000 - 1 = 0.10 -> pct_1 = 10.00, as if F_0 were 0.
        assertThat(relative).extracting(DepotEquityCurveService.RelativePoint::pct)
                .containsExactly(new BigDecimal("0.00"), new BigDecimal("10.00"));
    }

    // Regression: with every flow at 0, chain-linking telescopes to the old plain formula
    // (E_i / E_0 - 1) x 100. relativeIsPercentAgainstTheFirstReturnedPoint above already
    // covers this since daily(...) defaults external_flow to 0; this test adds a third point
    // to demonstrate the telescoping explicitly.
    @Test
    void allZeroFlowsTelescopeToTheOldPlainReturnFormula() {
        var repo = mock(DepotEquitySnapshotRepository.class);
        when(repo.series(any(), any(), any())).thenReturn(List.of(
                daily("2026-09-15", "100.00"),
                daily("2026-09-16", "110.00"),
                daily("2026-09-17", "90.00")));

        var relative = service(repo).curve("conn-1", "1m").relative();

        // r_1 = 110/100 - 1 = 0.10; r_2 = 90/110 - 1 = -0.181818...
        // cumulative_1 = 1.10 -> pct_1 = 10.00 = 110/100 - 1, matches the old formula.
        // cumulative_2 = 1.10 * (90/110) = 90/100 = 0.90 -> pct_2 = -10.00 = 90/100 - 1.
        assertThat(relative).extracting(DepotEquityCurveService.RelativePoint::pct)
                .containsExactly(
                        new BigDecimal("0.00"),
                        new BigDecimal("10.00"),
                        new BigDecimal("-10.00"));
    }

    @Test
    void intervalEquityOfZeroYieldsNoReturnJumpInsteadOfDividingByZero() {
        var repo = mock(DepotEquitySnapshotRepository.class);
        when(repo.series(any(), any(), any())).thenReturn(List.of(
                daily("2026-09-15", "100.00"),
                daily("2026-09-16", "0.00"),
                daily("2026-09-17", "50.00")));

        var relative = service(repo).curve("conn-1", "1m").relative();

        // r_1 = 0/100 - 1 = -1 -> cumulative_1 = 0 -> pct_1 = -100.00.
        // E_1 = 0 is the previous equity for interval 2 -> by definition r_2 = 0 (no
        // division by zero, no spurious jump): cumulative_2 = 0 * 1 = 0 -> pct_2 = -100.00,
        // even though equity actually recovered to 50 (there is nothing to be relative to).
        assertThat(relative).extracting(DepotEquityCurveService.RelativePoint::pct)
                .containsExactly(
                        new BigDecimal("0.00"),
                        new BigDecimal("-100.00"),
                        new BigDecimal("-100.00"));
    }

    // Symmetric case: a zero baseline equity re-anchors the chain rather than dividing by
    // zero. The interval immediately after E_{i-1} = 0 counts as 0% by the r_i = 0 rule, and
    // the next interval then resumes normal computation from the new (nonzero) baseline.
    @Test
    void zeroBaselineEquityReanchorsTheChainInsteadOfDividingByZero() {
        var repo = mock(DepotEquitySnapshotRepository.class);
        when(repo.series(any(), any(), any())).thenReturn(List.of(
                daily("2026-09-15", "0.00"),
                daily("2026-09-16", "100.00"),
                daily("2026-09-17", "200.00")));

        var relative = service(repo).curve("conn-1", "1m").relative();

        // E_0 = 0 -> r_1 = 0 by definition -> cumulative_1 = 1 -> pct_1 = 0.00 (re-anchored,
        // not a division-by-zero crash and not a spurious jump to the raw 100 EUR gain).
        // r_2 = 200/100 - 1 = 1.0, computed normally from the now-nonzero E_1 = 100 ->
        // cumulative_2 = 1 * 2 = 2 -> pct_2 = 100.00.
        assertThat(relative).extracting(DepotEquityCurveService.RelativePoint::pct)
                .containsExactly(
                        new BigDecimal("0.00"),
                        new BigDecimal("0.00"),
                        new BigDecimal("100.00"));
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
