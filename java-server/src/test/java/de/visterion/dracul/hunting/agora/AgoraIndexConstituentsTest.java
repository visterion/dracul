package de.visterion.dracul.hunting.agora;

import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The index universe behind strigoi-lazarus. An index that cannot be fetched, or that comes
 * back with zero constituents, must be reported as UNAVAILABLE — never as a healthy empty
 * universe, which is exactly how the empty-watchlist no-op hid for weeks.
 */
class AgoraIndexConstituentsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgoraIndexConstituents indexOf(String json) {
        AgoraClient agora = mock(AgoraClient.class);
        when(agora.callTool(eq("get_index_constituents"), any())).thenReturn(MAPPER.readTree(json));
        return new AgoraIndexConstituents(agora);
    }

    @Test
    void parsesSymbolsAndNames() {
        var result = indexOf("""
                {"index":"sp500","constituents":[
                  {"symbol":"AAA","name":"Alpha Inc","sector":"Industrials","dateAdded":"1999-01-01"},
                  {"symbol":"bbb","name":"Beta Corp","sector":"Energy","dateAdded":"2001-02-03"}]}
                """).constituents("sp500");

        assertThat(result.health().isHealthy()).isTrue();
        assertThat(result.items()).extracting(IndexConstituent::symbol).containsExactly("AAA", "BBB");
        assertThat(result.items()).extracting(IndexConstituent::companyName)
                .containsExactly("Alpha Inc", "Beta Corp");
    }

    @Test
    void skipsRowsWithoutASymbol() {
        var result = indexOf("""
                {"index":"sp500","constituents":[
                  {"symbol":"","name":"Nameless"},
                  {"name":"No symbol key"},
                  {"symbol":"CCC","name":"Gamma"}]}
                """).constituents("sp500");

        assertThat(result.items()).extracting(IndexConstituent::symbol).containsExactly("CCC");
    }

    @Test
    void emptyConstituentListIsUnavailableNotHealthy() {
        var result = indexOf("{\"index\":\"sp500\",\"constituents\":[]}").constituents("sp500");

        assertThat(result.health().isHealthy()).isFalse();
        assertThat(result.health().detail()).contains("sp500");
    }

    @Test
    void agoraFailureIsUnavailable() {
        AgoraClient agora = mock(AgoraClient.class);
        when(agora.callTool(eq("get_index_constituents"), any()))
                .thenThrow(new AgoraUnavailableException("boom"));

        var result = new AgoraIndexConstituents(agora).constituents("sp500");

        assertThat(result.health().isHealthy()).isFalse();
        assertThat(result.health().detail()).contains("boom");
    }
}
