package de.visterion.dracul.strigoi.lazarus;

import de.visterion.dracul.hunting.agora.AgoraPriceRange;
import de.visterion.dracul.hunting.agora.IndexConstituent;
import de.visterion.dracul.hunting.agora.PriceRange;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The cheap pre-filter that makes a market-wide lazarus universe affordable, and — just as
 * important — that COUNTS what it lost. Every drop here (no history for a symbol, a spent
 * wall-clock budget, a dead source) used to be invisible; each one now shows up as a number the
 * controller turns into {@code partial} / {@code truncated} health.
 */
class LazarusUniverseServiceTest {

    private static IndexConstituent c(String symbol) {
        return new IndexConstituent(symbol, symbol + " Inc", "Industrials");
    }

    private static List<IndexConstituent> universe(String... symbols) {
        List<IndexConstituent> out = new ArrayList<>();
        for (String s : symbols) out.add(c(s));
        return out;
    }

    private static PriceRange range(String symbol, String close, String low) {
        return new PriceRange(symbol, new BigDecimal(close), new BigDecimal(low), new BigDecimal("99"));
    }

    @Test
    void keepsOnlySymbolsWithinTheMargin() {
        AgoraPriceRange probe = mock(AgoraPriceRange.class);
        when(probe.range52w("NEAR")).thenReturn(range("NEAR", "11", "10"));   // +10 %
        when(probe.range52w("FAR")).thenReturn(range("FAR", "20", "10"));     // +100 %
        var service = new LazarusUniverseService(probe);

        var scan = service.preScreen(universe("NEAR", "FAR"), 0.25, 60_000L, 10, 0);

        assertThat(scan.shortlist()).extracting(LazarusUniverseService.PreScreened::symbol)
                .containsExactly("NEAR");
        assertThat(scan.shortlist().getFirst().currentPrice()).isEqualTo(11.0);
        assertThat(scan.shortlist().getFirst().pctAboveLow()).isEqualTo(0.10, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(scan.screened()).isEqualTo(2);
        assertThat(scan.probeFailed()).isZero();
        assertThat(scan.unscreened()).isZero();
        assertThat(scan.sourceDown()).isFalse();
    }

    @Test
    void countsPerSymbolFailuresWithoutDroppingTheRest() {
        AgoraPriceRange probe = mock(AgoraPriceRange.class);
        when(probe.range52w("NODATA")).thenReturn(null);                       // Agora answered, no history
        when(probe.range52w("BOOM")).thenThrow(new AgoraUnavailableException("nope"));
        when(probe.range52w("GOOD")).thenReturn(range("GOOD", "10.5", "10"));
        var service = new LazarusUniverseService(probe);

        var scan = service.preScreen(universe("NODATA", "BOOM", "GOOD"), 0.25, 60_000L, 10, 0);

        assertThat(scan.shortlist()).extracting(LazarusUniverseService.PreScreened::symbol)
                .containsExactly("GOOD");
        assertThat(scan.probeFailed()).isEqualTo(2);
        assertThat(scan.screened()).isEqualTo(3);
        assertThat(scan.sourceDown()).isFalse();
    }

    @Test
    void stopsWhenTheWallClockBudgetIsSpentAndReportsTheRestUnscreened() {
        AgoraPriceRange probe = mock(AgoraPriceRange.class);
        AtomicLong clock = new AtomicLong(0);
        // every probe burns 400 ms of the 700 ms budget -> A and B fit, C and D never run
        when(probe.range52w(anyString())).thenAnswer(i -> {
            clock.addAndGet(400);
            return range(i.getArgument(0), "10.5", "10");
        });
        var service = new LazarusUniverseService(probe, clock::get);

        var scan = service.preScreen(universe("A", "B", "C", "D"), 0.25, 700L, 10, 0);

        assertThat(scan.screened()).isEqualTo(2);
        assertThat(scan.unscreened()).isEqualTo(2);
        assertThat(scan.shortlist()).hasSize(2);
        assertThat(scan.sourceDown()).isFalse();
    }

    @Test
    void abortsAfterAConsecutiveFailureRunAndFlagsTheSourceDown() {
        AgoraPriceRange probe = mock(AgoraPriceRange.class);
        when(probe.range52w(anyString())).thenThrow(new AgoraUnavailableException("agora down"));
        var service = new LazarusUniverseService(probe);

        var scan = service.preScreen(universe("A", "B", "C", "D", "E"), 0.25, 60_000L, 2, 0);

        assertThat(scan.sourceDown()).isTrue();
        assertThat(scan.screened()).isEqualTo(2);
        assertThat(scan.unscreened()).isEqualTo(3);
        assertThat(scan.shortlist()).isEmpty();
    }

    /** A single success resets the run counter — a scattering of history-less symbols must not
     *  be mistaken for an outage. */
    @Test
    void aSuccessResetsTheConsecutiveFailureRun() {
        AgoraPriceRange probe = mock(AgoraPriceRange.class);
        when(probe.range52w("A")).thenReturn(null);
        when(probe.range52w("B")).thenReturn(range("B", "10.5", "10"));
        when(probe.range52w("C")).thenReturn(null);
        var service = new LazarusUniverseService(probe);

        var scan = service.preScreen(universe("A", "B", "C"), 0.25, 60_000L, 2, 0);

        assertThat(scan.sourceDown()).isFalse();
        assertThat(scan.screened()).isEqualTo(3);
    }

    /** When the budget truncates the universe, consecutive runs must not re-screen the same
     *  head of the list forever — the offset rotates the entry point so coverage is eventual. */
    @Test
    void rotationOffsetMovesTheEntryPointAndWrapsAround() {
        AgoraPriceRange probe = mock(AgoraPriceRange.class);
        AtomicLong clock = new AtomicLong(0);
        when(probe.range52w(anyString())).thenAnswer(i -> {
            clock.addAndGet(400);
            return range(i.getArgument(0), "10.5", "10");
        });
        var service = new LazarusUniverseService(probe, clock::get);

        var scan = service.preScreen(universe("A", "B", "C", "D"), 0.25, 700L, 10, 3);

        assertThat(scan.shortlist()).extracting(LazarusUniverseService.PreScreened::symbol)
                .containsExactly("D", "A");
    }

    @Test
    void emptyUniverseScansNothing() {
        var service = new LazarusUniverseService(mock(AgoraPriceRange.class));

        var scan = service.preScreen(List.of(), 0.25, 60_000L, 10, 0);

        assertThat(scan.shortlist()).isEmpty();
        assertThat(scan.screened()).isZero();
        assertThat(scan.unscreened()).isZero();
    }
}
