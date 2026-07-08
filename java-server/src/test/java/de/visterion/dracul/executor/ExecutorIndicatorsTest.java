package de.visterion.dracul.executor;

import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class ExecutorIndicatorsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private JsonNode json(String s) { return mapper.readTree(s); }

    @Test void parsesAtrSwingAndReference() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_indicators"), any())).thenReturn(json("""
            {"values":[
               {"label":"atr","available":true,"value":"2.5"},
               {"label":"swing_low","available":true,"value":"92.0"}
             ],"currentClose":"100.0"}
            """));

        ExecutorIndicators.Levels levels = new ExecutorIndicators(client, mapper).levels("ACME", 22, 20);

        assertThat(levels.available()).isTrue();
        assertThat(levels.atr()).isEqualByComparingTo("2.5");
        assertThat(levels.swingLow()).isEqualByComparingTo("92.0");
        assertThat(levels.referencePrice()).isEqualByComparingTo("100.0");
    }

    @Test void unavailableIndicatorSkipped() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_indicators"), any())).thenReturn(json("""
            {"values":[
               {"label":"atr","available":false,"error":"insufficient history"},
               {"label":"swing_low","available":true,"value":"92.0"}
             ],"currentClose":"100.0"}
            """));

        ExecutorIndicators.Levels levels = new ExecutorIndicators(client, mapper).levels("ACME", 22, 20);

        assertThat(levels.atr()).isNull();
        assertThat(levels.available()).isFalse();
    }

    @Test void agoraUnavailableReturnsUnavailable() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_indicators"), any())).thenThrow(new AgoraUnavailableException("down"));

        ExecutorIndicators.Levels levels = new ExecutorIndicators(client, mapper).levels("ACME", 22, 20);

        assertThat(levels.available()).isFalse();
        assertThat(levels.atr()).isNull();
        assertThat(levels.swingLow()).isNull();
        assertThat(levels.referencePrice()).isNull();
    }
}
