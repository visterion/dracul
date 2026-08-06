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

    /** Every chunk resolving nothing IS a dead source, and the pass must still stop there rather
     *  than burn the rest of the universe. Setup adjusted for the chunk unit (chunk size 1 makes
     *  each symbol its own chunk call, which is the shape this test always described); the intent
     *  — a genuine outage still aborts the pass and flags it — is unchanged. */
    @Test
    void abortsAfterAConsecutiveFailureRunAndFlagsTheSourceDown() {
        AgoraPriceRange probe = PriceRangeMocks.batching();
        when(probe.range52w(anyString())).thenThrow(new AgoraUnavailableException("agora down"));
        var service = new LazarusUniverseService(probe, () -> 0L, 1);

        var scan = service.preScreen(universe("A", "B", "C", "D", "E"), 0.25, 60_000L, 2, 0);

        assertThat(scan.sourceDown()).isTrue();
        assertThat(scan.screened()).isEqualTo(2);
        assertThat(scan.unscreened()).isEqualTo(3);
        assertThat(scan.shortlist()).isEmpty();
    }

    /** A single resolved range clears the run — a scattering of unusable bodies must not be
     *  mistaken for an outage. */
    @Test
    void aSuccessResetsTheConsecutiveFailureRun() {
        AgoraPriceRange probe = PriceRangeMocks.batching();
        when(probe.range52w("A")).thenReturn(RangeProbe.unusable());
        when(probe.range52w("B")).thenReturn(range("B", "10.5", "10"));
        when(probe.range52w("C")).thenReturn(RangeProbe.unusable());
        var service = new LazarusUniverseService(probe, () -> 0L, 1);

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

    /** A chunk of nothing but young symbols does not RESET the run either — an outage running
     *  through a young listing is still an outage, and must still stop the pass. */
    @Test
    void aYoungSymbolDoesNotMaskAGenuineFailureRun() {
        AgoraPriceRange probe = PriceRangeMocks.batching();
        when(probe.range52w("A")).thenThrow(new AgoraUnavailableException("agora down"));
        when(probe.range52w("YOUNG")).thenReturn(RangeProbe.notEligible());
        when(probe.range52w("C")).thenThrow(new AgoraUnavailableException("agora down"));
        var service = new LazarusUniverseService(probe, () -> 0L, 1);

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
     *  degradation — and a RUN of such chunks is what a dead source looks like: the pass stops and
     *  reports the rest unscreened. Setup adjusted to the chunk unit (threshold 2 chunks of 2
     *  symbols instead of 3 symbols); the intent — every chunk failing still trips and still stops
     *  the walk — is unchanged. */
    @Test
    void aFailedChunkCountsItsSymbolsAndCanStillTripSourceDown() {
        AgoraPriceRange probe = mock(AgoraPriceRange.class);
        when(probe.range52wBatch(anyList())).thenThrow(new AgoraUnavailableException("agora down"));
        var service = new LazarusUniverseService(probe, () -> 0L, 2);

        var scan = service.preScreen(universe("A", "B", "C", "D", "E", "F", "G", "H"),
                0.25, 60_000L, 2, 0);

        assertThat(scan.sourceDown()).isTrue();
        // stopped at the boundary of the SECOND dead chunk — the answers already paid for are kept
        assertThat(scan.screened()).isEqualTo(4);
        assertThat(scan.probeFailed()).isEqualTo(4);
        assertThat(scan.unscreened()).isEqualTo(4);
    }

    // -------------------------------------------------------- source-down counts chunks (S26)

    /**
     * THE production regression of 2026-08-06. One transient Alpaca page error inside a 90-symbol
     * chunk made Agora discard 37 partially read symbols — correctly, a truncated series reads
     * downstream as "insufficient history" — and the symbol-counting heuristic read those 37
     * adjacent failures as a dead Agora: {@code screened=410 … unscreened=80 sourceDown=true} out
     * of a 490-symbol universe, on a source that answered every other chunk fine. One dead chunk
     * followed by chunks that resolve is not an outage; the walk must run to the end.
     */
    @Test
    void oneDeadChunkAmongHealthyOnesNeitherFlagsTheSourceNorStopsTheWalk() {
        AgoraPriceRange probe = mock(AgoraPriceRange.class);
        when(probe.range52wBatch(anyList())).thenAnswer(i -> {
            List<String> symbols = i.getArgument(0);
            if (symbols.contains("SYNA")) throw new AgoraUnavailableException("chunk incomplete");
            Map<String, RangeProbe> out = new java.util.LinkedHashMap<>();
            for (String s : symbols) out.put(s, range(s, "10.5", "10"));
            return out;
        });
        var service = new LazarusUniverseService(probe, () -> 0L, 4);

        var scan = service.preScreen(universe("SYNA", "SYNB", "SYNC", "SYND",
                "SYNE", "SYNF", "SYNG", "SYNH", "SYNI", "SYNJ", "SYNK", "SYNL"), 0.25, 60_000L, 2, 0);

        assertThat(scan.sourceDown()).isFalse();
        assertThat(scan.screened()).isEqualTo(12);          // the walk reached the end
        assertThat(scan.unscreened()).isZero();
        assertThat(scan.probeFailed()).isEqualTo(4);        // the dead chunk is still counted, loudly
        assertThat(scan.shortlist()).extracting(LazarusUniverseService.PreScreened::symbol)
                .containsExactly("SYNE", "SYNF", "SYNG", "SYNH", "SYNI", "SYNJ", "SYNK", "SYNL");
        verify(probe, times(3)).range52wBatch(anyList());
    }

    /**
     * A source that answers is a source that answers, however few of a chunk's symbols it could
     * serve: one resolved range clears the run. This is the second half of the same fix — the
     * production chunk resolved 63 of 90 and was read as dead. The losses are still degradations
     * ({@code probeFailed} → {@code partial}); they are simply not evidence about the SOURCE.
     */
    @Test
    void aChunkThatResolvesOnlyAFewSymbolsNeverTripsHoweverManyAreUnusable() {
        AgoraPriceRange probe = mock(AgoraPriceRange.class);
        when(probe.range52wBatch(anyList())).thenAnswer(i -> {
            List<String> symbols = i.getArgument(0);
            Map<String, RangeProbe> out = new java.util.LinkedHashMap<>();
            for (String s : symbols) out.put(s, RangeProbe.unusable());
            out.put(symbols.getFirst(), range(symbols.getFirst(), "10.5", "10"));   // exactly one lives
            return out;
        });
        var service = new LazarusUniverseService(probe, () -> 0L, 5);

        var scan = service.preScreen(universe("SYNA", "SYNB", "SYNC", "SYND", "SYNE",
                "SYNF", "SYNG", "SYNH", "SYNI", "SYNJ"), 0.25, 60_000L, 2, 0);

        assertThat(scan.sourceDown()).isFalse();
        assertThat(scan.screened()).isEqualTo(10);
        assertThat(scan.probeFailed()).isEqualTo(8);        // 4 unusable per chunk, still degradations
        assertThat(scan.shortlist()).extracting(LazarusUniverseService.PreScreened::symbol)
                .containsExactly("SYNA", "SYNF");
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

    /**
     * The unit lives in the property NAME, in both places that carry a default. The rename is the
     * point: {@code LAZARUS_MAX_CONSECUTIVE_FAILURES} meant 10 SYMBOLS, and silently re-reading an
     * operator's 10 as 10 CHUNKS would put the threshold at 1000 symbols — a heuristic that can
     * never trip inside a 490-symbol universe. A stale env var of the old name is inert instead,
     * and the pass falls back to the documented default of 2 chunks.
     */
    @Test
    void theThresholdIsPinnedInChunksAndTheOldSymbolPropertyIsGone() throws Exception {
        String yaml = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/application.yaml"));
        assertThat(yaml).contains("max-consecutive-dead-chunks: ${LAZARUS_MAX_CONSECUTIVE_DEAD_CHUNKS:2}");
        assertThat(yaml).doesNotContain("${LAZARUS_MAX_CONSECUTIVE_FAILURES");

        String controller = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/de/visterion/dracul/strigoi/lazarus/StrigoiLazarusWebhookController.java"));
        assertThat(controller).contains("${dracul.strigoi.lazarus.max-consecutive-dead-chunks:2}");
        assertThat(controller).doesNotContain("max-consecutive-failures");
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
