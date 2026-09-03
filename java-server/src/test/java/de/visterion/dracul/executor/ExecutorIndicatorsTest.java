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

    /** A body whose TOP-LEVEL available is false (no spec produced a value) is data, not an outage,
     *  and now reaches this parser. Every level must stay null and the bundle unavailable — an ATR
     *  read as zero would size a stop at the entry price. */
    @Test void topLevelUnavailablePayloadYieldsNoLevels() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_indicators"), any())).thenReturn(json("""
            {"symbol":"SYNTH","currentClose":"143.21","asOf":"2026-08-05","available":false,
             "values":[
               {"label":"atr","available":false,"error":"insufficient history for atr"},
               {"label":"swing_low","available":false,"error":"insufficient history for lowest"}
             ]}
            """));

        ExecutorIndicators.Levels levels = new ExecutorIndicators(client, mapper).levels("SYNTH", 22, 20);

        assertThat(levels.available()).isFalse();
        assertThat(levels.atr()).isNull();
        assertThat(levels.swingLow()).isNull();
        assertThat(levels.referencePrice()).isEqualByComparingTo("143.21");
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

    /** Test 23. The short ATR MUST carry an explicit label. Agora derives a default label from the
     *  indicator NAME only, so two unlabelled `atr` specs collide and Agora rejects the duplicate
     *  with available:false — the short ATR would silently never arrive.
     *  Mutation: drop `.put("label", "atr_short")` from the request, or issue a second
     *  get_indicators call instead of adding the spec to the same one. */
    @Test void requestsShortAtrInSameCallWithExplicitLabel() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        org.mockito.ArgumentCaptor<tools.jackson.databind.node.ObjectNode> args =
                org.mockito.ArgumentCaptor.forClass(tools.jackson.databind.node.ObjectNode.class);
        when(client.callTool(eq("get_indicators"), any())).thenReturn(json("""
            {"values":[
               {"label":"atr","available":true,"value":"2.5"},
               {"label":"atr_short","available":true,"value":"4.0"},
               {"label":"swing_low","available":true,"value":"92.0"}
             ],"currentClose":"100.0"}
            """));

        ExecutorIndicators.Levels levels = new ExecutorIndicators(client, mapper, 5)
                .levels("ACME", 22, 20);

        Mockito.verify(client).callTool(eq("get_indicators"), args.capture());
        JsonNode specs = args.getValue().path("indicators");
        assertThat(specs).hasSize(3);
        assertThat(specs.get(0).path("name").asString()).isEqualTo("atr");
        assertThat(specs.get(0).path("params").path("period").asInt()).isEqualTo(22);
        assertThat(specs.get(0).has("label")).as("the long ATR keeps the default label").isFalse();
        assertThat(specs.get(1).path("name").asString()).isEqualTo("atr");
        assertThat(specs.get(1).path("params").path("period").asInt()).isEqualTo(5);
        assertThat(specs.get(1).path("label").asString()).isEqualTo("atr_short");

        assertThat(levels.atr()).isEqualByComparingTo("2.5");
        assertThat(levels.atrShort()).isEqualByComparingTo("4.0");
        assertThat(levels.atrEff()).isEqualByComparingTo("4.0");
    }

    /** Test 24. Mutation: propagate the null (atrEff() returning null), or throw on it. A symbol
     *  with too few bars for ATR5 must keep trading on ATR22. */
    @Test void atrShortUnavailableFallsBackToAtr22() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_indicators"), any())).thenReturn(json("""
            {"values":[
               {"label":"atr","available":true,"value":"2.5"},
               {"label":"atr_short","available":false,"error":"insufficient history for atr"},
               {"label":"swing_low","available":true,"value":"92.0"}
             ],"currentClose":"100.0"}
            """));

        ExecutorIndicators.Levels levels = new ExecutorIndicators(client, mapper, 5)
                .levels("ACME", 22, 20);

        assertThat(levels.atrShort()).isNull();
        assertThat(levels.atrEff()).isEqualByComparingTo("2.5");
    }

    /** Test 24b. Load-bearing: `available` gates closeBySymbol/atrBySymbol in MaintenancePipeline,
     *  so adding atrShort to it would disable the hard trigger AND the ratchet for a whole run on
     *  any symbol with too little history for ATR5.
     *  Mutation: add `&& atrShort != null` to the availability expression. */
    @Test void levelsAvailabilityIsIndependentOfShortAtr() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_indicators"), any())).thenReturn(json("""
            {"values":[
               {"label":"atr","available":true,"value":"2.5"},
               {"label":"atr_short","available":false,"error":"insufficient history for atr"},
               {"label":"swing_low","available":true,"value":"92.0"}
             ],"currentClose":"100.0"}
            """));

        ExecutorIndicators.Levels levels = new ExecutorIndicators(client, mapper, 5)
                .levels("ACME", 22, 20);

        assertThat(levels.available()).isTrue();
    }

    /** The short ATR never NARROWS the window: atrEff is a max, not a replacement. */
    @Test void shorterAtrBelowAtr22DoesNotNarrowAtrEff() {
        AgoraClient client = Mockito.mock(AgoraClient.class);
        when(client.callTool(eq("get_indicators"), any())).thenReturn(json("""
            {"values":[
               {"label":"atr","available":true,"value":"2.5"},
               {"label":"atr_short","available":true,"value":"1.1"}
             ],"currentClose":"100.0"}
            """));

        ExecutorIndicators.Levels levels = new ExecutorIndicators(client, mapper, 5)
                .levels("ACME", 22, 20);

        assertThat(levels.atrEff()).isEqualByComparingTo("2.5");
    }
}
