package de.visterion.dracul.report;

import de.visterion.dracul.gropar.ExitSignal;
import de.visterion.dracul.gropar.ExitSignalRepository;
import de.visterion.dracul.watchlist.PositionRisk;
import de.visterion.dracul.watchlist.WatchlistItem;
import de.visterion.dracul.watchlist.WatchlistRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.*;

class MorningReportServiceTest {

    private WatchlistItem held(String id, String sym, double entry, double shares) {
        WatchlistItem m = mock(WatchlistItem.class);
        when(m.id()).thenReturn(id);
        when(m.ticker()).thenReturn(sym);
        when(m.companyName()).thenReturn(sym + " Inc");
        when(m.tag()).thenReturn("HELD");
        when(m.owner()).thenReturn("u@x.com");
        when(m.entryPrice()).thenReturn(entry);
        when(m.shareCount()).thenReturn(shares);
        return m;
    }

    private MorningReportService svc(List<WatchlistItem> all,
            Map<String, PositionRisk> risk, List<ExitSignal> signals) {
        WatchlistRepository wl = mock(WatchlistRepository.class);
        ExitSignalRepository es = mock(ExitSignalRepository.class);
        when(wl.findAllByUser("u@x.com")).thenReturn(all);
        when(wl.positionRiskByItemId()).thenReturn(risk);
        when(es.findLatestByUser("u@x.com", 100)).thenReturn(signals);
        return new MorningReportService(wl, es);
    }

    @Test
    void ordersSellThenTrimThenHoldByDistance() {
        var a = held("1", "AAA", 100, 30);  // HOLD, close 95 stop 80 -> dist 15.8%
        var b = held("2", "BBB", 50, 10);   // SELL
        var c = held("3", "CCC", 20, 100);  // HOLD, close 21 stop 20 -> dist 4.76% (closer)
        var risk = Map.of(
                "1", new PositionRisk("1", "2026-01-01", new BigDecimal("70"),
                        new BigDecimal("80"), new BigDecimal("160"), new BigDecimal("95"), null),
                "2", new PositionRisk("2", "2026-01-01", new BigDecimal("40"),
                        new BigDecimal("45"), new BigDecimal("70"), new BigDecimal("48"), null),
                "3", new PositionRisk("3", "2026-01-01", new BigDecimal("15"),
                        new BigDecimal("20"), new BigDecimal("30"), new BigDecimal("21"), null));
        var signals = List.of(
                new ExitSignal("s2", "2", "BBB", "SELL", List.of(), -4.0, "INVALIDATED",
                        "raus", 0.9, "run", "2026-06-22T22:00:00Z"));
        // 1 and 3 have no signal -> default HOLD.

        MorningReport r = svc(List.of(a, b, c), risk, signals).build("u@x.com");

        assertThat(r.positions()).extracting(MorningReportLine::symbol)
                .containsExactly("BBB", "CCC", "AAA"); // SELL, then HOLDs closest-first
        assertThat(r.sellCount()).isEqualTo(1);
        assertThat(r.holdCount()).isEqualTo(2);
    }

    @Test
    void trimTicketIsOneThirdSellTicketIsFull() {
        var s = held("1", "AAA", 100, 30);
        var risk = Map.of("1", new PositionRisk("1", "2026-01-01", new BigDecimal("70"),
                new BigDecimal("80"), new BigDecimal("160"), new BigDecimal("95"), null));
        var trim = List.of(new ExitSignal("x", "1", "AAA", "TRIM", List.of(), 5.0,
                "WEAKENING", "teilverkauf", 0.6, "run", "2026-06-22T22:00:00Z"));

        var line = svc(List.of(s), risk, trim).build("u@x.com").positions().get(0);
        assertThat(line.ticket().shares()).isEqualTo(10.0);     // floor(30/3)
        assertThat(line.ticket().side()).isEqualTo("TRIM");

        var sell = List.of(new ExitSignal("y", "1", "AAA", "SELL", List.of(), 5.0,
                "INVALIDATED", "raus", 0.9, "run", "2026-06-22T22:00:00Z"));
        var line2 = svc(List.of(s), risk, sell).build("u@x.com").positions().get(0);
        assertThat(line2.ticket().shares()).isEqualTo(30.0);
    }

    @Test
    void positionWithoutSnapshotStillAppearsAsHold() {
        var s = held("1", "AAA", 100, 30);
        var line = svc(List.of(s), Map.of(), List.of()).build("u@x.com")
                .positions().get(0);
        assertThat(line.action()).isEqualTo("HOLD");
        assertThat(line.activeStop()).isNull();
        assertThat(line.distanceToStopPct()).isNull();
        assertThat(line.ticket().shares()).isEqualTo(0.0); // HOLD ticket
    }

    @Test
    void computesSignedDistanceToStop() {
        // price above stop: (95-80)/95*100 = 15.789...
        var a = held("1", "AAA", 100, 30);
        var riskAbove = Map.of("1", new PositionRisk("1", "2026-01-01", new BigDecimal("70"),
                new BigDecimal("80"), new BigDecimal("160"), new BigDecimal("95"), null));
        var line = svc(List.of(a), riskAbove, List.of()).build("u@x.com").positions().get(0);
        assertThat(line.distanceToStopPct()).isCloseTo(15.789, within(0.01));

        // price below stop: (70-80)/70*100 = -14.285... (sign-inversion guard)
        var riskBelow = Map.of("1", new PositionRisk("1", "2026-01-01", new BigDecimal("70"),
                new BigDecimal("80"), new BigDecimal("160"), new BigDecimal("70"), null));
        var line2 = svc(List.of(a), riskBelow, List.of()).build("u@x.com").positions().get(0);
        assertThat(line2.distanceToStopPct()).isCloseTo(-14.285, within(0.01));
    }

    @Test
    void breachedPositionWithoutSignalBecomesSell() {
        var a = held("1", "AAA", 100, 30);          // close 72 < stop 98 -> breached
        var risk = Map.of("1", new PositionRisk("1", "2026-01-01", new BigDecimal("64"),
                new BigDecimal("98"), new BigDecimal("169"), new BigDecimal("72"), null));
        var line = svc(List.of(a), risk, List.of()).build("u@x.com").positions().get(0);
        assertThat(line.action()).isEqualTo("SELL");
        assertThat(line.rationale()).contains("aktivem Stop");
        assertThat(line.ticket().shares()).isEqualTo(30.0); // full SELL ticket
    }

    @Test
    void breachedPositionWithHoldSignalIsOverriddenToSell() {
        var a = held("1", "AAA", 100, 30);
        var risk = Map.of("1", new PositionRisk("1", "2026-01-01", new BigDecimal("64"),
                new BigDecimal("98"), new BigDecimal("169"), new BigDecimal("72"), null));
        var hold = List.of(new ExitSignal("h", "1", "AAA", "HOLD", List.of(), -28.0,
                "INTACT", "halten", 0.5, "run", "2026-06-22T22:00:00Z"));
        var line = svc(List.of(a), risk, hold).build("u@x.com").positions().get(0);
        assertThat(line.action()).isEqualTo("SELL");
    }

    @Test
    void breachedPositionWithLlmTrimKeepsTrim() {
        var a = held("1", "AAA", 100, 30);
        var risk = Map.of("1", new PositionRisk("1", "2026-01-01", new BigDecimal("64"),
                new BigDecimal("98"), new BigDecimal("169"), new BigDecimal("72"), null));
        var trim = List.of(new ExitSignal("t", "1", "AAA", "TRIM", List.of(), -28.0,
                "WEAKENING", "teilverkauf", 0.6, "run", "2026-06-22T22:00:00Z"));
        var line = svc(List.of(a), risk, trim).build("u@x.com").positions().get(0);
        assertThat(line.action()).isEqualTo("TRIM");
    }

    @Test
    void unbreachedHoldStaysHold() {
        var a = held("1", "AAA", 100, 30);          // close 95 > stop 80 -> not breached
        var risk = Map.of("1", new PositionRisk("1", "2026-01-01", new BigDecimal("70"),
                new BigDecimal("80"), new BigDecimal("160"), new BigDecimal("95"), null));
        var line = svc(List.of(a), risk, List.of()).build("u@x.com").positions().get(0);
        assertThat(line.action()).isEqualTo("HOLD");
    }

    @Test
    void nonHeldAndOtherOwnersExcluded() {
        WatchlistItem watched = mock(WatchlistItem.class);
        when(watched.id()).thenReturn("9");
        when(watched.tag()).thenReturn("WATCHED");
        when(watched.entryPrice()).thenReturn(null);
        when(watched.shareCount()).thenReturn(null);
        var held = held("1", "AAA", 100, 30);
        var r = svc(List.of(held, watched), Map.of(), List.of()).build("u@x.com");
        assertThat(r.positions()).hasSize(1);
    }
}
