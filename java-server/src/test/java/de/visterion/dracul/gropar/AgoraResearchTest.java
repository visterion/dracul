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

    @Test void mapsGetIndicatorsBundle() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_indicators"), any())).thenReturn(json("""
            {"symbol":"AAPL","currentClose":109,"atr":2,"atrAvailable":true,
             "chandelierStop":103,"chandelierBreached":false,
             "maFast":105,"maFastAvailable":true,"maSlow":100,"maSlowAvailable":true,
             "maCrossState":"BULLISH","high52w":120,"low52w":90,"window52wAvailable":true,"available":true}
            """));
        ExitTa ta = new AgoraResearch(client).exitTa("AAPL", 22, new BigDecimal("3.0"), 50, 200, 250);
        assertThat(ta.atr()).isEqualByComparingTo("2");
        assertThat(ta.atrAvailable()).isTrue();
        assertThat(ta.chandelierStop()).isEqualByComparingTo("103");
        assertThat(ta.maFast()).isEqualByComparingTo("105");
        assertThat(ta.maSlow()).isEqualByComparingTo("100");
        assertThat(ta.maCrossState()).isEqualTo("BULLISH");
        assertThat(ta.high52w()).isEqualByComparingTo("120");
        assertThat(ta.window52wAvailable()).isTrue();
    }

    @Test void passesParamsAsToolArgs() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_indicators"), any())).thenReturn(json("{\"available\":true,\"atrAvailable\":false}"));
        new AgoraResearch(client).exitTa("AAPL", 22, new BigDecimal("3.0"), 50, 200, 250);
        ArgumentCaptor<JsonNode> cap = ArgumentCaptor.forClass(JsonNode.class);
        Mockito.verify(client).callTool(eq("get_indicators"), cap.capture());
        JsonNode args = cap.getValue();
        assertThat(args.get("symbol").asString()).isEqualTo("AAPL");
        assertThat(args.get("period").asInt()).isEqualTo(22);
        assertThat(new java.math.BigDecimal(args.get("multiple").asString())).isEqualByComparingTo("3.0");
        assertThat(args.get("maFast").asInt()).isEqualTo(50);
        assertThat(args.get("maSlow").asInt()).isEqualTo(200);
        assertThat(args.get("minBars52w").asInt()).isEqualTo(250);
    }

    @Test void unavailableOnAgoraFailure() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_indicators"), any())).thenThrow(new AgoraUnavailableException("down"));
        ExitTa ta = new AgoraResearch(client).exitTa("AAPL", 22, new BigDecimal("3.0"), 50, 200, 250);
        assertThat(ta.atrAvailable()).isFalse();
        assertThat(ta.maCrossState()).isEqualTo("NEUTRAL");
        assertThat(ta.chandelierStop()).isNull();
    }
}
