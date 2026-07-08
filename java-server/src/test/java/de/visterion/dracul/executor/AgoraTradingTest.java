package de.visterion.dracul.executor;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgoraTradingTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private JsonNode json(String s) { return mapper.readTree(s); }

    /** Capturing subclass: stubs the HTTP seam, records the last (tool, args) call. */
    private static class CapturingAgoraTrading extends AgoraTrading {
        String capturedTool;
        JsonNode capturedArgs;
        JsonNode canned;

        CapturingAgoraTrading(ObjectMapper mapper) {
            super("http://x", "tkn", mapper);
        }

        @Override
        protected JsonNode call(String tool, JsonNode args) {
            this.capturedTool = tool;
            this.capturedArgs = args;
            return canned;
        }
    }

    @Test void placeBracketBuildsArgsAndUnwraps() {
        CapturingAgoraTrading trading = new CapturingAgoraTrading(mapper);
        trading.canned = json("{\"output\":{\"broker_order_id\":\"ord-1\",\"status\":\"accepted\"}}");

        JsonNode result = trading.placeBracket("saxo-sim", "ACME", "BUY",
                new BigDecimal("10"), null, new BigDecimal("95"), null);

        assertThat(trading.capturedTool).isEqualTo("place_bracket_order");
        JsonNode args = trading.capturedArgs;
        assertThat(args.path("connection").asString()).isEqualTo("saxo-sim");
        assertThat(args.path("symbol").asString()).isEqualTo("ACME");
        assertThat(args.path("side").asString()).isEqualTo("BUY");
        assertThat(args.has("qty")).isTrue();
        assertThat(args.has("stop_loss")).isTrue();
        assertThat(args.has("limit_price")).isFalse();
        assertThat(args.has("take_profit")).isFalse();

        assertThat(result.path("broker_order_id").asString("")).isEqualTo("ord-1");
    }

    @Test void accountUnwrapsOutput() {
        CapturingAgoraTrading trading = new CapturingAgoraTrading(mapper);
        trading.canned = json("{\"output\":{\"cash\":\"1000\",\"currency\":\"USD\"}}");

        JsonNode result = trading.account("saxo-sim");

        assertThat(trading.capturedTool).isEqualTo("get_account");
        assertThat(trading.capturedArgs.path("connection").asString()).isEqualTo("saxo-sim");
        assertThat(result.path("cash").asString("")).isEqualTo("1000");
    }

    @Test void unavailableEnvelopeThrows() {
        CapturingAgoraTrading trading = new CapturingAgoraTrading(mapper);
        trading.canned = json("{\"output\":{\"available\":false,\"error\":\"no session\"}}");

        assertThatThrownBy(() -> trading.account("saxo-sim"))
                .isInstanceOf(AgoraTradingException.class)
                .hasMessageContaining("no session");
    }
}
