package de.visterion.dracul.report;

import de.visterion.dracul.gropar.ExitSignal;
import de.visterion.dracul.gropar.ExitSignalRepository;
import de.visterion.dracul.position.HeldPosition;
import de.visterion.dracul.position.HeldPositionService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.*;

class MorningReportServiceTest {

    private static final String CONNECTION = "depot-1";

    /** A held depot position (⨝ context). {@code close} is folded into marketValue
     *  (= close * quantity) since HeldPosition carries no explicit currentClose field --
     *  the service derives it from marketValue / quantity. */
    private HeldPosition held(String sym, double entry, double shares, BigDecimal activeStop, BigDecimal close) {
        BigDecimal qty = BigDecimal.valueOf(shares);
        BigDecimal marketValue = close == null ? null : close.multiply(qty);
        return new HeldPosition(sym, qty, BigDecimal.valueOf(entry), marketValue, null, null,
                null, null, null, null, null, activeStop, null, null);
    }

    private MorningReportService svc(List<HeldPosition> positions, List<ExitSignal> signals) {
        HeldPositionService hp = mock(HeldPositionService.class);
        ExitSignalRepository es = mock(ExitSignalRepository.class);
        when(hp.openPositions(CONNECTION)).thenReturn(positions);
        when(es.findLatestByUser("u@x.com", 100)).thenReturn(signals);
        return new MorningReportService(hp, es, CONNECTION);
    }

    @Test
    void ordersSellThenTrimThenHoldByDistance() {
        var a = held("AAA", 100, 30, new BigDecimal("80"), new BigDecimal("95"));   // HOLD, dist 15.8%
        var b = held("BBB", 50, 10, new BigDecimal("45"), new BigDecimal("48"));    // SELL
        var c = held("CCC", 20, 100, new BigDecimal("20"), new BigDecimal("21"));   // HOLD, dist 4.76% (closer)
        var signals = List.of(
                new ExitSignal("s2", null, "BBB", "SELL", List.of(), -4.0, "INVALIDATED",
                        "raus", 0.9, "run", "2026-06-22T22:00:00Z"));
        // AAA and CCC have no signal -> default HOLD.

        MorningReport r = svc(List.of(a, b, c), signals).build("u@x.com");

        assertThat(r.positions()).extracting(MorningReportLine::symbol)
                .containsExactly("BBB", "CCC", "AAA"); // SELL, then HOLDs closest-first
        assertThat(r.sellCount()).isEqualTo(1);
        assertThat(r.holdCount()).isEqualTo(2);
    }

    @Test
    void groparExitSignalRekeyedBySymbolAppearsAgainstMatchingPosition() {
        // gropar writes exit_signals.watchlist_item_id = NULL and keys by symbol only --
        // the rekey under test is what makes this signal actually surface here.
        var a = held("AAA", 100, 30, new BigDecimal("80"), new BigDecimal("95"));
        var signal = new ExitSignal("s1", null, "AAA", "SELL", List.of(), 5.0,
                "INVALIDATED", "raus", 0.9, "run", "2026-06-22T22:00:00Z");

        var line = svc(List.of(a), List.of(signal)).build("u@x.com").positions().get(0);

        assertThat(line.action()).isEqualTo("SELL");
        assertThat(line.rationale()).isEqualTo("raus");
    }

    @Test
    void trimTicketIsOneThirdSellTicketIsFull() {
        var s = held("AAA", 100, 30, new BigDecimal("80"), new BigDecimal("95"));
        var trim = List.of(new ExitSignal("x", null, "AAA", "TRIM", List.of(), 5.0,
                "WEAKENING", "teilverkauf", 0.6, "run", "2026-06-22T22:00:00Z"));

        var line = svc(List.of(s), trim).build("u@x.com").positions().get(0);
        assertThat(line.ticket().shares()).isEqualTo(10.0);     // floor(30/3)
        assertThat(line.ticket().side()).isEqualTo("TRIM");

        var sell = List.of(new ExitSignal("y", null, "AAA", "SELL", List.of(), 5.0,
                "INVALIDATED", "raus", 0.9, "run", "2026-06-22T22:00:00Z"));
        var line2 = svc(List.of(s), sell).build("u@x.com").positions().get(0);
        assertThat(line2.ticket().shares()).isEqualTo(30.0);
    }

    @Test
    void fractionalPositionKeepsDecimalTicketShares() {
        var s = held("TSM", 100, 0.73, new BigDecimal("80"), new BigDecimal("95"));

        var trim = List.of(new ExitSignal("x", null, "TSM", "TRIM", List.of(), 5.0,
                "WEAKENING", "teilverkauf", 0.6, "run", "2026-06-22T22:00:00Z"));
        var line = svc(List.of(s), trim).build("u@x.com").positions().get(0);
        assertThat(line.shareCount()).isEqualTo(0.73);        // same source as HELD watchlist items
        assertThat(line.ticket().shares()).isEqualTo(0.2433); // third of 0.73, 4 dp — not floored to 0

        var sell = List.of(new ExitSignal("y", null, "TSM", "SELL", List.of(), 5.0,
                "INVALIDATED", "raus", 0.9, "run", "2026-06-22T22:00:00Z"));
        var line2 = svc(List.of(s), sell).build("u@x.com").positions().get(0);
        assertThat(line2.ticket().shares()).isEqualTo(0.73);
    }

    @Test
    void positionWithoutSnapshotStillAppearsAsHold() {
        var s = held("AAA", 100, 30, null, null);
        var line = svc(List.of(s), List.of()).build("u@x.com")
                .positions().get(0);
        assertThat(line.action()).isEqualTo("HOLD");
        assertThat(line.activeStop()).isNull();
        assertThat(line.distanceToStopPct()).isNull();
        assertThat(line.ticket().shares()).isEqualTo(0.0); // HOLD ticket
    }

    @Test
    void computesSignedDistanceToStop() {
        // price above stop: (95-80)/95*100 = 15.789...
        var a = held("AAA", 100, 30, new BigDecimal("80"), new BigDecimal("95"));
        var line = svc(List.of(a), List.of()).build("u@x.com").positions().get(0);
        assertThat(line.distanceToStopPct()).isCloseTo(15.789, within(0.01));

        // price below stop: (70-80)/70*100 = -14.285... (sign-inversion guard)
        var b = held("AAA", 100, 30, new BigDecimal("80"), new BigDecimal("70"));
        var line2 = svc(List.of(b), List.of()).build("u@x.com").positions().get(0);
        assertThat(line2.distanceToStopPct()).isCloseTo(-14.285, within(0.01));
    }

    @Test
    void breachedPositionWithoutSignalBecomesSell() {
        var a = held("AAA", 100, 30, new BigDecimal("98"), new BigDecimal("72")); // close 72 < stop 98 -> breached
        var line = svc(List.of(a), List.of()).build("u@x.com").positions().get(0);
        assertThat(line.action()).isEqualTo("SELL");
        assertThat(line.rationale()).contains("aktivem Stop");
        assertThat(line.ticket().shares()).isEqualTo(30.0); // full SELL ticket
    }

    @Test
    void breachedPositionWithHoldSignalIsOverriddenToSell() {
        var a = held("AAA", 100, 30, new BigDecimal("98"), new BigDecimal("72"));
        var hold = List.of(new ExitSignal("h", null, "AAA", "HOLD", List.of(), -28.0,
                "INTACT", "halten", 0.5, "run", "2026-06-22T22:00:00Z"));
        var line = svc(List.of(a), hold).build("u@x.com").positions().get(0);
        assertThat(line.action()).isEqualTo("SELL");
    }

    @Test
    void breachedPositionWithLlmTrimKeepsTrim() {
        var a = held("AAA", 100, 30, new BigDecimal("98"), new BigDecimal("72"));
        var trim = List.of(new ExitSignal("t", null, "AAA", "TRIM", List.of(), -28.0,
                "WEAKENING", "teilverkauf", 0.6, "run", "2026-06-22T22:00:00Z"));
        var line = svc(List.of(a), trim).build("u@x.com").positions().get(0);
        assertThat(line.action()).isEqualTo("TRIM");
    }

    @Test
    void unbreachedHoldStaysHold() {
        var a = held("AAA", 100, 30, new BigDecimal("80"), new BigDecimal("95")); // close 95 > stop 80 -> not breached
        var line = svc(List.of(a), List.of()).build("u@x.com").positions().get(0);
        assertThat(line.action()).isEqualTo("HOLD");
    }

    @Test
    void depotDownYieldsEmptyPortfolioSectionNoThrow() {
        HeldPositionService hp = mock(HeldPositionService.class);
        ExitSignalRepository es = mock(ExitSignalRepository.class);
        when(hp.openPositions(CONNECTION)).thenReturn(List.of()); // fail-soft: depot unreachable
        when(es.findLatestByUser("u@x.com", 100)).thenReturn(List.of());

        var r = new MorningReportService(hp, es, CONNECTION).build("u@x.com");

        assertThat(r.positions()).isEmpty();
        assertThat(r.sellCount()).isZero();
        assertThat(r.trimCount()).isZero();
        assertThat(r.holdCount()).isZero();
    }
}
