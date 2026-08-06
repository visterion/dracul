package de.visterion.dracul.hunting.agora;

import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The cheap 52-week-range pre-filter probe: one {@code 52w_range} spec returns both the 52-week
 * low and the current close, so the expensive per-symbol fundamentals call is only spent on names
 * that are actually near their low. Two routes in — {@code get_indicators} for one symbol,
 * {@code get_indicators_batch} for a chunk — and they must classify identically.
 */
class AgoraPriceRangeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgoraPriceRange probeOf(String json) {
        AgoraClient agora = mock(AgoraClient.class);
        when(agora.callTool(eq("get_indicators"), any())).thenReturn(MAPPER.readTree(json));
        return new AgoraPriceRange(agora);
    }

    @Test
    void readsCloseAndRange() {
        RangeProbe p = probeOf("""
                {"symbol":"SYNTH","currentClose":"11.00","values":[
                  {"label":"52w_range","available":true,"value":{"low":"10.00","high":"40.00"}}]}
                """).range52w("SYNTH");

        assertThat(p.kind()).isEqualTo(RangeProbe.Kind.OK);
        PriceRange r = p.range();
        assertThat(r).isNotNull();
        Assertions.assertThat(r.currentClose()).isEqualByComparingTo("11.00");
        Assertions.assertThat(r.low52()).isEqualByComparingTo("10.00");
        Assertions.assertThat(r.high52()).isEqualByComparingTo("40.00");
        assertThat(r.pctAboveLow()).isEqualTo(0.10, org.assertj.core.data.Offset.offset(1e-9));
    }

    /** The production shape of a symbol younger than 52 weeks (FDXF, HONA, Q on 2026-08-05):
     *  Agora answered with a close and a per-value {@code available:false}, and its top-level flag
     *  is false because that was the only spec. This is a permanent property of the instrument,
     *  not a source degradation — the caller must be able to see the difference, or every run
     *  reports partial and the daily-analysis alarm fires nightly for three young listings. */
    @Test
    void youngSymbolPayloadIsNotEligibleRatherThanADegradation() {
        RangeProbe p = probeOf("""
                {"symbol":"SYNTH","currentClose":143.21,"asOf":"2026-08-05","values":[
                  {"label":"52w_range","available":false,"error":"insufficient history for 52w_range"}],
                 "available":false}
                """).range52w("SYNTH");

        assertThat(p.kind()).isEqualTo(RangeProbe.Kind.NOT_ELIGIBLE);
        assertThat(p.range()).isNull();
    }

    /** …and it stays not-eligible even without a close: nothing about a symbol with no 52-week
     *  window is going to be usable, and calling that a degradation sends the operator hunting. */
    @Test
    void youngSymbolWithoutACloseIsStillNotEligible() {
        assertThat(probeOf("""
                {"symbol":"SYNTH","values":[{"label":"52w_range","available":false}]}
                """).range52w("SYNTH").kind()).isEqualTo(RangeProbe.Kind.NOT_ELIGIBLE);
    }

    @Test
    void missingCurrentCloseIsUnusable() {
        assertThat(probeOf("""
                {"symbol":"SYNTH","values":[
                  {"label":"52w_range","available":true,"value":{"low":"10.00","high":"40.00"}}]}
                """).range52w("SYNTH").kind()).isEqualTo(RangeProbe.Kind.UNUSABLE);
    }

    @Test
    void zeroCurrentCloseIsUnusable() {
        assertThat(probeOf("""
                {"symbol":"SYNTH","currentClose":"0","values":[
                  {"label":"52w_range","available":true,"value":{"low":"10.00","high":"40.00"}}]}
                """).range52w("SYNTH").kind()).isEqualTo(RangeProbe.Kind.UNUSABLE);
    }

    @Test
    void nonPositiveLowIsUnusable() {
        assertThat(probeOf("""
                {"symbol":"SYNTH","currentClose":"11.00","values":[
                  {"label":"52w_range","available":true,"value":{"low":"0","high":"40.00"}}]}
                """).range52w("SYNTH").kind()).isEqualTo(RangeProbe.Kind.UNUSABLE);
    }

    /** A body that does not carry the spec we asked for: nothing about the instrument explains
     *  that, so it is an upstream problem and must be counted as one. */
    @Test
    void aBodyWithoutTheRequestedSpecIsUnusable() {
        assertThat(probeOf("""
                {"symbol":"SYNTH","currentClose":"11.00","values":[]}
                """).range52w("SYNTH").kind()).isEqualTo(RangeProbe.Kind.UNUSABLE);
    }

    // ---------------------------------------------------------------- the batched route (S18)

    private AgoraPriceRange batchProbeOf(String json) {
        AgoraClient agora = mock(AgoraClient.class);
        when(agora.callTool(eq("get_indicators_batch"), any())).thenReturn(MAPPER.readTree(json));
        return new AgoraPriceRange(agora);
    }

    /**
     * One call, four verdicts — and every requested symbol carries one. The fourth is the one the
     * batch route adds: SYND was asked for and is not in {@code results} at all. Agora's batch tool
     * contracts to emit an entry for every symbol it was given, so a gap is a malformed answer —
     * a degradation. Calling it NOT_ELIGIBLE would let a lost symbol pose as a young listing and
     * put the 2026-08-05 conflation straight back in through the other door, this time with the
     * degradation uncounted.
     */
    @Test
    void aBatchClassifiesEverySymbolIncludingTheOneMissingFromResults() {
        var probes = batchProbeOf("""
                {"requested":4,"returned":1,"available":true,"results":[
                  {"symbol":"SYNA","currentClose":"11.00","available":true,"values":[
                    {"label":"52w_range","available":true,"value":{"low":"10.00","high":"40.00"}}]},
                  {"symbol":"SYNB","currentClose":"143.21","available":false,"values":[
                    {"label":"52w_range","available":false,"error":"insufficient history for 52w_range"}]},
                  {"symbol":"SYNC","available":false,"error":"no data for SYNC"}
                ]}
                """).range52wBatch(List.of("SYNA", "SYNB", "SYNC", "SYND"));

        assertThat(probes).containsOnlyKeys("SYNA", "SYNB", "SYNC", "SYND");
        assertThat(probes.get("SYNA").kind()).isEqualTo(RangeProbe.Kind.OK);
        Assertions.assertThat(probes.get("SYNA").range().low52()).isEqualByComparingTo("10.00");
        // has bars, too short a history: a permanent property of the instrument, not a degradation
        assertThat(probes.get("SYNB").kind()).isEqualTo(RangeProbe.Kind.NOT_ELIGIBLE);
        // the shape the batch tool emits for a symbol no provider served (e.g. a non-US suffix
        // Alpaca's multi-symbol endpoint does not carry): an entry, but no values array
        assertThat(probes.get("SYNC").kind()).isEqualTo(RangeProbe.Kind.UNUSABLE);
        // absent from results entirely
        assertThat(probes.get("SYND").kind()).isEqualTo(RangeProbe.Kind.UNUSABLE);
    }

    /** The batch route asks for the SAME spec and reaches the SAME verdicts as the single-symbol
     *  one — a 52-week low that depends on which route fetched it is worse than no pre-filter. */
    @Test
    void theBatchRouteClassifiesLikeTheSingleSymbolRoute() {
        var probes = batchProbeOf("""
                {"results":[
                  {"symbol":"SYNA","values":[
                    {"label":"52w_range","available":true,"value":{"low":"10.00","high":"40.00"}}]},
                  {"symbol":"SYNB","currentClose":"11.00","values":[
                    {"label":"52w_range","available":true,"value":{"low":"0","high":"40.00"}}]},
                  {"symbol":"SYNC","currentClose":"11.00","values":[]}
                ]}
                """).range52wBatch(List.of("SYNA", "SYNB", "SYNC"));

        assertThat(probes.get("SYNA").kind()).isEqualTo(RangeProbe.Kind.UNUSABLE);  // no close
        assertThat(probes.get("SYNB").kind()).isEqualTo(RangeProbe.Kind.UNUSABLE);  // low <= 0
        assertThat(probes.get("SYNC").kind()).isEqualTo(RangeProbe.Kind.UNUSABLE);  // spec missing
    }

    @Test
    void anEmptySymbolListMakesNoCall() {
        AgoraClient agora = mock(AgoraClient.class);

        assertThat(new AgoraPriceRange(agora).range52wBatch(List.of())).isEmpty();
        verifyNoInteractions(agora);
    }

    /** Agora REJECTS an oversized batch instead of truncating it, so a caller that would produce
     *  one has a bug — and finds out here rather than by screening a silently shortened index. */
    @Test
    void aBatchOverAgorasCapIsRefusedBeforeItIsSent() {
        AgoraClient agora = mock(AgoraClient.class);
        List<String> tooMany = java.util.stream.IntStream.range(0, AgoraPriceRange.MAX_BATCH_SYMBOLS + 1)
                .mapToObj(i -> "SYN" + i).toList();

        assertThatThrownBy(() -> new AgoraPriceRange(agora).range52wBatch(tooMany))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(AgoraPriceRange.MAX_BATCH_SYMBOLS));
        verifyNoInteractions(agora);
    }

    /** An outage on the batch call PROPAGATES too: the caller then knows nothing about any symbol
     *  in the chunk, which is a different statement from "these symbols have no range". */
    @Test
    void aBatchOutagePropagates() {
        AgoraClient agora = mock(AgoraClient.class);
        when(agora.callTool(eq("get_indicators_batch"), any()))
                .thenThrow(new AgoraUnavailableException("agora down"));

        assertThatThrownBy(() -> new AgoraPriceRange(agora).range52wBatch(List.of("SYNA", "SYNB")))
                .isInstanceOf(AgoraUnavailableException.class);
    }

    /** An outage must PROPAGATE — the caller uses it to stop burning dead calls on the rest of
     *  the universe, which a null (= "no data for this symbol") could never signal. */
    @Test
    void agoraOutagePropagates() {
        AgoraClient agora = mock(AgoraClient.class);
        when(agora.callTool(eq("get_indicators"), any()))
                .thenThrow(new AgoraUnavailableException("agora down"));

        assertThatThrownBy(() -> new AgoraPriceRange(agora).range52w("AAA"))
                .isInstanceOf(AgoraUnavailableException.class);
    }
}
