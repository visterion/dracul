package de.visterion.dracul.hivemem;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.*;

class HiveMemClientParseTest {

    @Test void parsesToolTextToJson() {
        JsonNode n = HiveMemClient.parseToolText("{\"cells\":[{\"id\":\"c1\",\"score\":0.9}]}", false);
        assertThat(n.get("cells").get(0).get("id").asString()).isEqualTo("c1");
        assertThat(n.get("cells").get(0).get("score").decimalValue()).isEqualByComparingTo("0.9");
    }

    @Test void errorFlagThrowsUnavailable() {
        assertThatThrownBy(() -> HiveMemClient.parseToolText("{\"available\":false,\"error\":\"down\"}", true))
                .isInstanceOf(HiveMemUnavailableException.class);
    }

    @Test void availableFalseBodyThrowsUnavailable() {
        // even without the MCP isError flag, an {available:false} body is unavailable
        assertThatThrownBy(() -> HiveMemClient.parseToolText("{\"available\":false,\"error\":\"x\"}", false))
                .isInstanceOf(HiveMemUnavailableException.class);
    }

    @Test void malformedJsonThrowsUnavailable() {
        assertThatThrownBy(() -> HiveMemClient.parseToolText("not json", false))
                .isInstanceOf(HiveMemUnavailableException.class);
    }
}
