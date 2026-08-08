package de.visterion.dracul.hunting.agora;

import de.visterion.dracul.marketdata.AgoraUnavailableException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A mocked {@link AgoraPriceRange} whose BATCH method answers out of its own single-symbol stubs.
 *
 * <p>Callers moved to {@link AgoraPriceRange#range52wBatch(List)} on 2026-08-06, but a test is far
 * clearer describing one symbol at a time ("NEAR trades 10 % above its low, BOOM is dead") than
 * assembling a map per chunk. This seam keeps that vocabulary: stub {@code range52w(symbol)} as
 * before and the chunked production path sees exactly the answers those stubs describe.
 *
 * <p>Two translations are deliberate. A symbol with no stub is left OUT of the map — that is the
 * real shape of a chunk that failed to answer for a symbol, and the caller must count it. And a
 * stub that throws is mapped to {@code UNUSABLE} for that one symbol: the batch wire has no
 * per-symbol exception, and both arrive at the same counter. Use a stub on
 * {@code range52wBatch} itself to model a whole chunk going down.
 */
public final class PriceRangeMocks {

    private PriceRangeMocks() {}

    public static AgoraPriceRange batching() {
        AgoraPriceRange probe = mock(AgoraPriceRange.class);
        wireBatchFromSingleStubs(probe);
        return probe;
    }

    /**
     * Wires {@code range52wBatch} on an already-existing {@link AgoraPriceRange} mock (e.g. one
     * injected via {@code @MockitoBean}) to answer out of that same mock's {@code range52w}
     * stubs, using the same two translations documented on {@link #batching()}. Use this when the
     * mock instance is provided by the test framework and cannot be swapped for {@link #batching()}.
     */
    public static void wireBatchFromSingleStubs(AgoraPriceRange probe) {
        when(probe.range52wBatch(anyList())).thenAnswer(inv -> {
            List<String> symbols = inv.getArgument(0);
            Map<String, RangeProbe> out = new LinkedHashMap<>();
            for (String symbol : symbols) {
                RangeProbe p;
                try {
                    p = probe.range52w(symbol);
                } catch (AgoraUnavailableException e) {
                    p = RangeProbe.unusable();
                }
                if (p != null) out.put(symbol, p);
            }
            return out;
        });
    }
}
