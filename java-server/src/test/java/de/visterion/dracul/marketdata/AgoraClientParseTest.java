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

    @Test void availableFalseBodyThrowsUnavailable() {
        // even without the MCP isError flag, an {available:false} body is unavailable
        assertThatThrownBy(() -> AgoraClient.parseToolText("{\"available\":false,\"error\":\"x\"}", false))
                .isInstanceOf(AgoraUnavailableException.class);
    }

    @Test void malformedJsonThrowsUnavailable() {
        assertThatThrownBy(() -> AgoraClient.parseToolText("not json", false))
                .isInstanceOf(AgoraUnavailableException.class);
    }
}
