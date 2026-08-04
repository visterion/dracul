package de.visterion.dracul.hunting.agora;

import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The cheap 52-week-range pre-filter probe: ONE {@code get_indicators} call per symbol returns
 * both the 52-week low and the current close, so the expensive per-symbol fundamentals call is
 * only spent on names that are actually near their low.
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
        PriceRange r = probeOf("""
                {"symbol":"AAA","currentClose":"11.00","values":[
                  {"label":"52w_range","available":true,"value":{"low":"10.00","high":"40.00"}}]}
                """).range52w("AAA");

        assertThat(r).isNotNull();
        Assertions.assertThat(r.currentClose()).isEqualByComparingTo("11.00");
        Assertions.assertThat(r.low52()).isEqualByComparingTo("10.00");
        Assertions.assertThat(r.high52()).isEqualByComparingTo("40.00");
        assertThat(r.pctAboveLow()).isEqualTo(0.10, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void unavailableIndicatorYieldsNullRatherThanAFakeZero() {
        assertThat(probeOf("""
                {"symbol":"AAA","currentClose":"11.00","values":[
                  {"label":"52w_range","available":false}]}
                """).range52w("AAA")).isNull();
    }

    @Test
    void missingCurrentCloseYieldsNull() {
        assertThat(probeOf("""
                {"symbol":"AAA","values":[
                  {"label":"52w_range","available":true,"value":{"low":"10.00","high":"40.00"}}]}
                """).range52w("AAA")).isNull();
    }

    @Test
    void nonPositiveLowYieldsNull() {
        assertThat(probeOf("""
                {"symbol":"AAA","currentClose":"11.00","values":[
                  {"label":"52w_range","available":true,"value":{"low":"0","high":"40.00"}}]}
                """).range52w("AAA")).isNull();
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
