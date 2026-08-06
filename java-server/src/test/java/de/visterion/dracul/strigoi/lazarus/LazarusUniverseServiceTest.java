package de.visterion.dracul.strigoi.lazarus;

import de.visterion.dracul.hunting.agora.AgoraPriceRange;
import de.visterion.dracul.hunting.agora.IndexConstituent;
import de.visterion.dracul.hunting.agora.PriceRange;
import de.visterion.dracul.hunting.agora.PriceRangeMocks;
import de.visterion.dracul.hunting.agora.RangeProbe;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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

    private static RangeProbe range(String symbol, String close, String low) {
        return RangeProbe.of(new PriceRange(symbol, new BigDecimal(close), new BigDecimal(low),
                new BigDecimal("99")));
    }

    @Test
    void keepsOnlySymbolsWithinTheMargin() {
        AgoraPriceRange probe = PriceRangeMocks.batching();
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
        AgoraPriceRange probe = PriceRangeMocks.batching();
        when(probe.range52w("NODATA")).thenReturn(RangeProbe.unusable());      // Agora answered unusably
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
        AgoraPriceRange probe = PriceRangeMocks.batching();
        AtomicLong clock = new AtomicLong(0);
        // every probe burns 400 ms of the 700 ms budget -> the first chunk of two fits, the second
        // never runs. The budget is checked per CHUNK, so the chunk size is what decides where the
        // pass can stop — hence an explicit 2 here rather than the production default.
        when(probe.range52w(anyString())).thenAnswer(i -> {
            clock.addAndGet(400);
            return range(i.getArgument(0), "10.5", "10");
        });
        var service = new LazarusUniverseService(probe, clock::get, 2);

        var scan = service.preScreen(universe("A", "B", "C", "D"), 0.25, 700L, 10, 0);

        assertThat(scan.screened()).isEqualTo(2);
        assertThat(scan.unscreened()).isEqualTo(2);
        assertThat(scan.shortlist()).hasSize(2);
        assertThat(scan.sourceDown()).isFalse();
    }

    @Test
    void abortsAfterAConsecutiveFailureRunAndFlagsTheSourceDown() {
        AgoraPriceRange probe = PriceRangeMocks.batching();
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
        AgoraPriceRange probe = PriceRangeMocks.batching();
        when(probe.range52w("A")).thenReturn(RangeProbe.unusable());
        when(probe.range52w("B")).thenReturn(range("B", "10.5", "10"));
        when(probe.range52w("C")).thenReturn(RangeProbe.unusable());
        var service = new LazarusUniverseService(probe);

        var scan = service.preScreen(universe("A", "B", "C"), 0.25, 60_000L, 2, 0);

        assertThat(scan.sourceDown()).isFalse();
        assertThat(scan.screened()).isEqualTo(3);
    }

    /** When the budget truncates the universe, consecutive runs must not re-screen the same
     *  head of the list forever — the offset rotates the entry point so coverage is eventual. */
    @Test
    void rotationOffsetMovesTheEntryPointAndWrapsAround() {
        AgoraPriceRange probe = PriceRangeMocks.batching();
        AtomicLong clock = new AtomicLong(0);
        when(probe.range52w(anyString())).thenAnswer(i -> {
            clock.addAndGet(400);
            return range(i.getArgument(0), "10.5", "10");
        });
        var service = new LazarusUniverseService(probe, clock::get, 2);

        var scan = service.preScreen(universe("A", "B", "C", "D"), 0.25, 700L, 10, 3);

        assertThat(scan.shortlist()).extracting(LazarusUniverseService.PreScreened::symbol)
                .containsExactly("D", "A");
    }

    @Test
    void consecutiveYoungSymbolsNeitherDegradeNorDeclareTheSourceDown() {
        AgoraPriceRange probe = PriceRangeMocks.batching();
        when(probe.range52w(anyString())).thenReturn(RangeProbe.notEligible());
        var service = new LazarusUniverseService(probe);

        // five in a row against a maxConsecutiveFailures of 2: under the old code, where a young
        // symbol shared probeFailed's counter, this aborted the pass at symbol 2 and declared a
        // perfectly healthy Agora down.
        var scan = service.preScreen(universe("A", "B", "C", "D", "E"), 0.25, 60_000L, 2, 0);

        assertThat(scan.sourceDown()).isFalse();
        assertThat(scan.probeFailed()).isZero();
        assertThat(scan.notEligible()).isEqualTo(5);
        assertThat(scan.screened()).isEqualTo(5);
        assertThat(scan.unscreened()).isZero();
    }

    /** Each bucket keeps its own number: one young listing, one unusable body, one dead call. */
    @Test
    void aMixedPassCountsEachLossInItsOwnBucket() {
        AgoraPriceRange probe = PriceRangeMocks.batching();
        when(probe.range52w("YOUNG")).thenReturn(RangeProbe.notEligible());
        when(probe.range52w("JUNK")).thenReturn(RangeProbe.unusable());
        when(probe.range52w("BOOM")).thenThrow(new AgoraUnavailableException("nope"));
        when(probe.range52w("GOOD")).thenReturn(range("GOOD", "10.5", "10"));
        var service = new LazarusUniverseService(probe);

        var scan = service.preScreen(universe("YOUNG", "JUNK", "BOOM", "GOOD"), 0.25, 60_000L, 10, 0);

        assertThat(scan.notEligible()).isEqualTo(1);
        assertThat(scan.probeFailed()).isEqualTo(2);
        assertThat(scan.screened()).isEqualTo(4);
        assertThat(scan.shortlist()).extracting(LazarusUniverseService.PreScreened::symbol)
                .containsExactly("GOOD");
        assertThat(scan.sourceDown()).isFalse();
    }

    /** A young symbol does not RESET the run counter either — an outage running through a young
     *  listing is still an outage, and must still stop the pass. */
    @Test
    void aYoungSymbolDoesNotMaskAGenuineFailureRun() {
        AgoraPriceRange probe = PriceRangeMocks.batching();
        when(probe.range52w("A")).thenThrow(new AgoraUnavailableException("agora down"));
        when(probe.range52w("YOUNG")).thenReturn(RangeProbe.notEligible());
        when(probe.range52w("C")).thenThrow(new AgoraUnavailableException("agora down"));
        var service = new LazarusUniverseService(probe);

        var scan = service.preScreen(universe("A", "YOUNG", "C"), 0.25, 60_000L, 2, 0);

        assertThat(scan.sourceDown()).isTrue();
        assertThat(scan.probeFailed()).isEqualTo(2);
        assertThat(scan.notEligible()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the chunked walk (S18)

    /** The whole point of chunking: a universe bigger than one chunk must produce exactly the
     *  shortlist and exactly the counters the per-symbol walk produced for the same data — only
     *  in ceil(n/chunk) calls instead of n. */
    @Test
    void aChunkedWalkReturnsTheSameShortlistAsAPerSymbolWalkWould() {
        AgoraPriceRange probe = PriceRangeMocks.batching();
        when(probe.range52w(anyString())).thenAnswer(i -> range(i.getArgument(0), "10.5", "10"));
        when(probe.range52w("FAR")).thenReturn(range("FAR", "50", "10"));
        when(probe.range52w("YOUNG")).thenReturn(RangeProbe.notEligible());
        when(probe.range52w("JUNK")).thenReturn(RangeProbe.unusable());
        var service = new LazarusUniverseService(probe, () -> 0L, 3);

        var scan = service.preScreen(universe("A", "B", "FAR", "YOUNG", "JUNK", "C", "D"),
                0.25, 60_000L, 10, 0);

        assertThat(scan.shortlist()).extracting(LazarusUniverseService.PreScreened::symbol)
                .containsExactly("A", "B", "C", "D");
        assertThat(scan.screened()).isEqualTo(7);
        assertThat(scan.notEligible()).isEqualTo(1);
        assertThat(scan.probeFailed()).isEqualTo(1);
        assertThat(scan.unscreened()).isZero();
        assertThat(scan.sourceDown()).isFalse();
        // three calls for seven symbols, not seven calls
        verify(probe, times(3)).range52wBatch(anyList());
    }

    /** A chunk that comes back COVERING FEWER SYMBOLS than it was handed is the failure this
     *  project keeps meeting: silent partial coverage that reads downstream like a quiet market.
     *  The unanswered symbols are degradations, exactly as if each had failed alone. */
    @Test
    void symbolsAChunkDoesNotAnswerForAreCountedAsDegradations() {
        AgoraPriceRange probe = mock(AgoraPriceRange.class);
        when(probe.range52wBatch(anyList())).thenReturn(Map.of("A", range("A", "10.5", "10")));
        var service = new LazarusUniverseService(probe, () -> 0L, 3);

        var scan = service.preScreen(universe("A", "B", "C"), 0.25, 60_000L, 10, 0);

        assertThat(scan.shortlist()).extracting(LazarusUniverseService.PreScreened::symbol)
                .containsExactly("A");
        assertThat(scan.screened()).isEqualTo(3);
        assertThat(scan.probeFailed()).isEqualTo(2);
        assertThat(scan.notEligible()).isZero();   // NOT young papers — a gap in the answer
        assertThat(scan.unscreened()).isZero();
    }

    /** A chunk whose CALL dies tells us nothing about any symbol in it, so every one of them is a
     *  degradation — and the run counter keeps counting symbols, so the threshold still means the
     *  same number it always did whatever the chunk size is. */
    @Test
    void aFailedChunkCountsItsSymbolsAndCanStillTripSourceDown() {
        AgoraPriceRange probe = mock(AgoraPriceRange.class);
        when(probe.range52wBatch(anyList())).thenThrow(new AgoraUnavailableException("agora down"));
        var service = new LazarusUniverseService(probe, () -> 0L, 4);

        var scan = service.preScreen(universe("A", "B", "C", "D", "E", "F"), 0.25, 60_000L, 3, 0);

        assertThat(scan.sourceDown()).isTrue();
        // stopped INSIDE the first chunk, at the third failure — not at the chunk boundary
        assertThat(scan.screened()).isEqualTo(3);
        assertThat(scan.probeFailed()).isEqualTo(3);
        assertThat(scan.unscreened()).isEqualTo(3);
    }

    /** A budget that expires mid-universe still reports the rest unscreened AND still advances the
     *  entry point far enough for the next run to continue rather than re-screen the same head. */
    @Test
    void aBudgetExpiringMidUniverseLeavesTheRestUnscreenedAndRotates() {
        AgoraPriceRange probe = PriceRangeMocks.batching();
        AtomicLong clock = new AtomicLong(0);
        when(probe.range52w(anyString())).thenAnswer(i -> {
            clock.addAndGet(100);
            return range(i.getArgument(0), "10.5", "10");
        });
        var service = new LazarusUniverseService(probe, clock::get, 2);

        var first = service.preScreen(universe("A", "B", "C", "D", "E", "F"), 0.25, 150L, 10, 0);
        assertThat(first.screened()).isEqualTo(2);
        assertThat(first.unscreened()).isEqualTo(4);
        assertThat(first.shortlist()).extracting(LazarusUniverseService.PreScreened::symbol)
                .containsExactly("A", "B");

        // the caller advances the offset by what was screened; the next pass picks up at C
        clock.set(0);
        var second = service.preScreen(universe("A", "B", "C", "D", "E", "F"), 0.25, 150L, 10,
                first.screened());
        assertThat(second.shortlist()).extracting(LazarusUniverseService.PreScreened::symbol)
                .containsExactly("C", "D");
    }

    @Test
    void emptyUniverseScansNothing() {
        var service = new LazarusUniverseService(PriceRangeMocks.batching());

        var scan = service.preScreen(List.of(), 0.25, 60_000L, 10, 0);

        assertThat(scan.shortlist()).isEmpty();
        assertThat(scan.screened()).isZero();
        assertThat(scan.unscreened()).isZero();
    }
}
