package de.visterion.dracul.marketdata;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.*;

class AgoraClientParseTest {

    @Test void parsesToolTextToJson() {
        JsonNode n = AgoraClient.parseToolText("{\"quotes\":[{\"symbol\":\"AAPL\",\"price\":190.5}]}", false);
        assertThat(n.get("quotes").get(0).get("symbol").asString()).isEqualTo("AAPL");
        assertThat(n.get("quotes").get(0).get("price").decimalValue()).isEqualByComparingTo("190.5");
    }

    @Test void errorFlagThrowsUnavailable() {
        assertThatThrownBy(() -> AgoraClient.parseToolText("{\"available\":false,\"error\":\"down\"}", true))
                .isInstanceOf(AgoraUnavailableException.class);
    }

    /** The SAME text is an outage only when the envelope says so — that is the whole point of the
     *  two-flag distinction, so it is asserted on one identical payload. */
    @Test void sameBodyIsAnOutageOnlyWhenTheEnvelopeSaysSo() {
        String text = "{\"symbol\":\"SYNTH\",\"currentClose\":10.0,\"asOf\":\"2026-08-05\","
                + "\"values\":[{\"label\":\"52w_range\",\"available\":false,"
                + "\"error\":\"insufficient history for 52w_range\"}],\"available\":false}";

        assertThatThrownBy(() -> AgoraClient.parseToolText(text, true))
                .isInstanceOf(AgoraUnavailableException.class);

        JsonNode n = AgoraClient.parseToolText(text, false);
        assertThat(n.path("symbol").asString()).isEqualTo("SYNTH");
        assertThat(n.path("available").asBoolean(true)).isFalse();
        assertThat(n.path("values").get(0).path("available").asBoolean(true)).isFalse();
    }

    /** A young listing has no 52-week range; Agora answered, so the body must reach the caller
     *  rather than be thrown away as "Agora unreachable". */
    @Test void payloadAvailableFalseIsDataNotAnOutage() {
        JsonNode n = AgoraClient.parseToolText(
                "{\"symbol\":\"SYNTH\",\"currentClose\":10.0,\"values\":[],\"available\":false}", false);
        assertThat(n.path("currentClose").decimalValue()).isEqualByComparingTo("10.0");
    }

    @Test void malformedJsonThrowsUnavailable() {
        assertThatThrownBy(() -> AgoraClient.parseToolText("not json", false))
                .isInstanceOf(AgoraUnavailableException.class);
    }
}
