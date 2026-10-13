package de.visterion.dracul.benchmark;

import de.visterion.dracul.depot.DepotEquityCurveService;
import de.visterion.dracul.depot.DepotService;
import de.visterion.dracul.depot.DepotUnavailableException;
import de.visterion.dracul.executor.ExecutorPosition;
import de.visterion.dracul.executor.ExecutorPositionFixtures;
import de.visterion.dracul.executor.ExecutorPositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static de.visterion.dracul.benchmark.BenchmarkFixtures.d;
import static de.visterion.dracul.benchmark.BenchmarkFixtures.series;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DepotBenchmarkServiceTest {

    private final DepotEquityCurveService curves = mock(DepotEquityCurveService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ExecutorPositionRepository> provider = mock(ObjectProvider.class);
    private final BenchmarkBars bars = mock(BenchmarkBars.class);
    private final DepotService depots = mock(DepotService.class);
    private final DepotBenchmarkService service = new DepotBenchmarkService(curves, provider, bars, depots, "depot-1",
            Clock.fixed(Instant.parse("2026-03-13T12:00:00Z"), ZoneOffset.UTC));

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @BeforeEach
    void defaults() {
        when(depots.isVisible(eq("depot-1"), anyString())).thenReturn(true);
        when(curves.curve("depot-1", "max")).thenReturn(new DepotEquityCurveService.EquityCurve("DAILY",
                List.of(new DepotEquityCurveService.CurvePoint("2026-03-02", bd("1000.00"), "MEASURED"),
                        new DepotEquityCurveService.CurvePoint("2026-03-06", bd("1025.00"), "MEASURED")),
                List.of(new DepotEquityCurveService.RelativePoint("2026-03-02", bd("0.00")),
                        new DepotEquityCurveService.RelativePoint("2026-03-06", bd("2.50"))),
                "EUR"));
        when(bars.fetch(eq("EUNL.DE"), anyInt())).thenReturn(series(d(2026, 2, 2), d(2026, 3, 13), "100",
                Map.of(d(2026, 3, 6), "101")));
    }

    @Test
    void invisibleConnectionHasNoBlockAndComputesNothing() {
        when(depots.isVisible("depot-1", "viewer@example.com")).thenReturn(false);
        assertThat(service.block("viewer@example.com")).isNull();
        verify(curves, never()).curve(anyString(), anyString());
    }

    @Test
    void aFailingVisibilityCheckHasNoBlock() {
        when(depots.isVisible("depot-1", "viewer@example.com")).thenThrow(new DepotUnavailableException("down"));
        assertThat(service.block("viewer@example.com")).isNull();
    }

    @Test
    void twrComesFromTheEquityCurveAndEunlCoversTheSameDates() {
        DepotBenchmark b = service.block("owner@example.com");
        assertThat(b.connection()).isEqualTo("depot-1");
        assertThat(b.from()).isEqualTo("2026-03-02");
        assertThat(b.to()).isEqualTo("2026-03-06");
        assertThat(b.twr()).isEqualByComparingTo("0.025");
        assertThat(b.benchmarkReturn()).isEqualByComparingTo("0.01");
        assertThat(b.excess()).isEqualByComparingTo("0.015");
        assertThat(b.byStrigoi()).isEmpty();
    }

    @Test
    void theBlockIsCachedWithinTheTtl() {
        service.block("owner@example.com");
        service.block("owner@example.com");
        verify(curves, times(1)).curve("depot-1", "max");
    }

    @Test
    void openPositionsAreMarkedWithTheirOwnBars() {
        ExecutorPositionRepository repo = mock(ExecutorPositionRepository.class);
        when(provider.getIfAvailable()).thenReturn(repo);
        ExecutorPosition open = ExecutorPositionFixtures.withoutKillLevel(7L, "depot-1", "SYNE.DE", "BUY",
                bd("20"), bd("50"), bd("45"), bd("45"), 1, bd("5"), List.of(), "sig-7", "strigoi-syna",
                "2026-03-02T14:00:00Z", null, "OPEN", null, null, null, 0, null, null, null, null, null, null,
                null, null, null, 0, null, null, null, null, null, null, false, null, "2026-03-02T14:00:05Z");
        when(repo.findBookForPnl("depot-1")).thenReturn(List.of(open));
        when(bars.fetch(eq("SYNE.DE"), anyInt())).thenReturn(series(d(2026, 3, 2), d(2026, 3, 10), "50",
                Map.of(d(2026, 3, 10), "55")));

        DepotBenchmark b = service.block("owner@example.com");

        assertThat(b.byStrigoi()).singleElement().satisfies(r -> {
            assertThat(r.strigoi()).isEqualTo("strigoi-syna");
            assertThat(r.openPositions()).isEqualTo(1);
            assertThat(r.returnEur()).isEqualByComparingTo("0.1");
        });
    }

    @Test
    void toInputMapsClosedAndOpenRows() {
        ExecutorPosition closed = ExecutorPositionFixtures.withoutKillLevel(1L, "depot-1", "SYNA", "BUY",
                bd("10"), bd("100"), bd("95"), bd("95"), 1, bd("5"), List.of(), "sig-1", "",
                "2026-03-02T14:00:00Z", null, "CLOSED", null, null, null, 0, bd("110"), null, "STOP",
                "2026-03-06T20:00:00Z", null, null, null, null, null, 0, null, null, null, null, null, null,
                false, null, "2026-03-02T14:00:05Z");
        DepotBenchmarkCalculator.PositionInput in = DepotBenchmarkService.toInput(closed);
        assertThat(in.strigoi()).isEqualTo("unknown");
        assertThat(in.entryDate()).isEqualTo(d(2026, 3, 2));
        assertThat(in.exitDate()).isEqualTo(d(2026, 3, 6));
        assertThat(in.exitPrice()).isEqualByComparingTo("110");
        assertThat(in.open()).isFalse();
    }
}
