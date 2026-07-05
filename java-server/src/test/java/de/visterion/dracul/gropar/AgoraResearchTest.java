package de.visterion.dracul.gropar;

import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class AgoraResearchTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private JsonNode json(String s) { return mapper.readTree(s); }

    /** Full happy-path values[] response maps into ExitTa; derived fields correct. */
    @Test void mapsGetIndicatorsValues() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_indicators"), any())).thenReturn(json("""
            {"symbol":"AAPL","currentClose":109,"asOf":"2025-01-30","available":true,
             "values":[
               {"label":"atr","available":true,"value":2},
               {"label":"chandelier_stop","available":true,"value":103},
               {"label":"ma50","available":true,"value":105},
               {"label":"ma200","available":true,"value":100},
               {"label":"52w_range","available":true,"value":{"high":120,"low":90}}
             ]}
            """));
        ExitTa ta = new AgoraResearch(client).exitTa("AAPL", 22, new BigDecimal("3.0"), 50, 200, 250);
        assertThat(ta.atr()).isEqualByComparingTo("2");
        assertThat(ta.atrAvailable()).isTrue();
        assertThat(ta.chandelierStop()).isEqualByComparingTo("103");
        assertThat(ta.maFast()).isEqualByComparingTo("105");
        assertThat(ta.maFastAvailable()).isTrue();
        assertThat(ta.maSlow()).isEqualByComparingTo("100");
        assertThat(ta.maSlowAvailable()).isTrue();
        assertThat(ta.high52w()).isEqualByComparingTo("120");
        assertThat(ta.low52w()).isEqualByComparingTo("90");
        assertThat(ta.window52wAvailable()).isTrue();
        // maFast(105) > maSlow(100) -> BULLISH; currentClose(109) > stop(103) -> not breached
        assertThat(ta.maCrossState()).isEqualTo("BULLISH");
        assertThat(ta.chandelierBreached()).isFalse();
    }

    /** currentClose below stop -> breached; fast below slow -> DEATH_CROSS. */
    @Test void derivesBreachAndDeathCross() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_indicators"), any())).thenReturn(json("""
            {"symbol":"AAPL","currentClose":100,"available":true,
             "values":[
               {"label":"chandelier_stop","available":true,"value":103},
               {"label":"ma50","available":true,"value":95},
               {"label":"ma200","available":true,"value":100}
             ]}
            """));
        ExitTa ta = new AgoraResearch(client).exitTa("AAPL", 22, new BigDecimal("3.0"), 50, 200, 250);
        assertThat(ta.chandelierBreached()).isTrue();
        assertThat(ta.maCrossState()).isEqualTo("DEATH_CROSS");
    }

    /** Per-value available:false -> null value, false flag; one MA unavailable -> NEUTRAL. */
    @Test void perValueUnavailableYieldsNullAndFalse() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_indicators"), any())).thenReturn(json("""
            {"symbol":"AAPL","currentClose":109,"available":true,
             "values":[
               {"label":"atr","available":false,"error":"insufficient history for atr"},
               {"label":"ma50","available":true,"value":105},
               {"label":"ma200","available":false,"error":"insufficient history for sma"}
             ]}
            """));
        ExitTa ta = new AgoraResearch(client).exitTa("AAPL", 22, new BigDecimal("3.0"), 50, 200, 250);
        assertThat(ta.atr()).isNull();
        assertThat(ta.atrAvailable()).isFalse();
        assertThat(ta.maFastAvailable()).isTrue();
        assertThat(ta.maSlowAvailable()).isFalse();
        assertThat(ta.maCrossState()).isEqualTo("NEUTRAL");
    }

    /** Request is built as the new indicators[] spec array (names, params, labels). */
    @Test void passesParamsAsIndicatorSpecs() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_indicators"), any())).thenReturn(json("{\"available\":true,\"values\":[]}"));
        new AgoraResearch(client).exitTa("AAPL", 22, new BigDecimal("3.0"), 50, 200, 250);
        ArgumentCaptor<JsonNode> cap = ArgumentCaptor.forClass(JsonNode.class);
        Mockito.verify(client).callTool(eq("get_indicators"), cap.capture());
        JsonNode args = cap.getValue();
        assertThat(args.get("symbol").asString()).isEqualTo("AAPL");
        JsonNode specs = args.get("indicators");
        assertThat(specs.isArray()).isTrue();
        assertThat(specs.size()).isEqualTo(5);
        assertThat(specs.get(0).get("name").asString()).isEqualTo("atr");
        assertThat(specs.get(0).get("params").get("period").asInt()).isEqualTo(22);
        assertThat(specs.get(1).get("name").asString()).isEqualTo("chandelier_stop");
        assertThat(specs.get(1).get("params").get("period").asInt()).isEqualTo(22);
        assertThat(new BigDecimal(specs.get(1).get("params").get("multiple").asString()))
                .isEqualByComparingTo("3.0");
        assertThat(specs.get(2).get("name").asString()).isEqualTo("sma");
        assertThat(specs.get(2).get("params").get("period").asInt()).isEqualTo(50);
        assertThat(specs.get(2).get("label").asString()).isEqualTo("ma50");
        assertThat(specs.get(3).get("name").asString()).isEqualTo("sma");
        assertThat(specs.get(3).get("params").get("period").asInt()).isEqualTo(200);
        assertThat(specs.get(3).get("label").asString()).isEqualTo("ma200");
        assertThat(specs.get(4).get("name").asString()).isEqualTo("52w_range");
        assertThat(specs.get(4).get("params").get("minBars").asInt()).isEqualTo(250);
    }

    /** Equal MAs -> BULLISH (equality falls into the else branch, not DEATH_CROSS). */
    @Test void equalMovingAveragesAreBullish() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_indicators"), any())).thenReturn(json("""
            {"symbol":"AAPL","currentClose":109,"available":true,
             "values":[
               {"label":"ma50","available":true,"value":100},
               {"label":"ma200","available":true,"value":100}
             ]}
            """));
        ExitTa ta = new AgoraResearch(client).exitTa("AAPL", 22, new BigDecimal("3.0"), 50, 200, 250);
        assertThat(ta.maCrossState()).isEqualTo("BULLISH");
    }

    /** Whole-call failure -> ExitTa.unavailable() (unchanged). */
    @Test void unavailableOnAgoraFailure() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_indicators"), any())).thenThrow(new AgoraUnavailableException("down"));
        ExitTa ta = new AgoraResearch(client).exitTa("AAPL", 22, new BigDecimal("3.0"), 50, 200, 250);
        assertThat(ta.atrAvailable()).isFalse();
        assertThat(ta.maCrossState()).isEqualTo("NEUTRAL");
        assertThat(ta.chandelierStop()).isNull();
    }
}
