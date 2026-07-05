package de.visterion.dracul.gropar;

import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Fetches Agora's generic get_indicators for gropar and maps it to a neutral ExitTa.
 *
 * <p>Requests atr, chandelier_stop, two SMAs (labelled ma50/ma200) and 52w_range as an
 * indicator-spec array; parses the numeric {@code values[]} back by label. The derived
 * exit signals {@code chandelierBreached} (close &lt; stop) and {@code maCrossState}
 * (fast vs slow) are computed here from Agora's own currentClose — the generic contract
 * no longer ships them. Never throws.</p>
 */
@Component
@ConditionalOnProperty(value = "dracul.gropar.enabled", havingValue = "true")
public class AgoraResearch {

    private final AgoraClient agora;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgoraResearch(AgoraClient agora) { this.agora = agora; }

    public ExitTa exitTa(String symbol, int atrPeriod, BigDecimal atrMultiple,
                         int maFast, int maSlow, int minBars52w) {
        ObjectNode args = mapper.createObjectNode();
        args.put("symbol", symbol);
        ArrayNode indicators = args.putArray("indicators");

        ObjectNode atr = indicators.addObject();
        atr.put("name", "atr");
        atr.putObject("params").put("period", atrPeriod);

        ObjectNode chandelier = indicators.addObject();
        chandelier.put("name", "chandelier_stop");
        ObjectNode chandelierParams = chandelier.putObject("params");
        chandelierParams.put("period", atrPeriod);
        chandelierParams.put("multiple", atrMultiple);

        ObjectNode ma50 = indicators.addObject();
        ma50.put("name", "sma");
        ma50.putObject("params").put("period", maFast);
        ma50.put("label", "ma50");

        ObjectNode ma200 = indicators.addObject();
        ma200.put("name", "sma");
        ma200.putObject("params").put("period", maSlow);
        ma200.put("label", "ma200");

        ObjectNode range = indicators.addObject();
        range.put("name", "52w_range");
        range.putObject("params").put("minBars", minBars52w);

        JsonNode r;
        try {
            r = agora.callTool("get_indicators", args);
        } catch (AgoraUnavailableException e) {
            return ExitTa.unavailable();
        }

        Map<String, JsonNode> values = index(r.path("values"));

        boolean atrAvailable = available(values, "atr");
        BigDecimal atrValue = scalar(values, "atr");
        BigDecimal chandelierStop = scalar(values, "chandelier_stop");
        boolean maFastAvailable = available(values, "ma50");
        BigDecimal maFastValue = scalar(values, "ma50");
        boolean maSlowAvailable = available(values, "ma200");
        BigDecimal maSlowValue = scalar(values, "ma200");
        boolean window52wAvailable = available(values, "52w_range");
        BigDecimal high52w = multi(values, "52w_range", "high");
        BigDecimal low52w = multi(values, "52w_range", "low");

        BigDecimal currentClose = bd(r, "currentClose");
        boolean chandelierBreached = chandelierStop != null && currentClose != null
                && currentClose.compareTo(chandelierStop) < 0;
        String maCrossState = (maFastAvailable && maSlowAvailable
                && maFastValue != null && maSlowValue != null)
                ? (maFastValue.compareTo(maSlowValue) < 0 ? "DEATH_CROSS" : "BULLISH")
                : "NEUTRAL";

        return new ExitTa(
                atrValue, atrAvailable,
                chandelierStop, chandelierBreached,
                maFastValue, maFastAvailable,
                maSlowValue, maSlowAvailable,
                maCrossState,
                high52w, low52w, window52wAvailable);
    }

    /** Index a values[] array by its label field. */
    private static Map<String, JsonNode> index(JsonNode values) {
        Map<String, JsonNode> byLabel = new HashMap<>();
        if (values.isArray()) {
            for (JsonNode v : values) byLabel.put(v.path("label").asString(""), v);
        }
        return byLabel;
    }

    /** Per-value availability flag (false when the label is absent). */
    private static boolean available(Map<String, JsonNode> values, String label) {
        JsonNode n = values.get(label);
        return n != null && n.path("available").asBoolean(false);
    }

    /** Scalar single-output value for a label (null when absent/unavailable). */
    private static BigDecimal scalar(Map<String, JsonNode> values, String label) {
        JsonNode n = values.get(label);
        return n == null ? null : bd(n, "value");
    }

    /** One sub-field of a multi-output value object for a label. */
    private static BigDecimal multi(Map<String, JsonNode> values, String label, String key) {
        JsonNode n = values.get(label);
        return n == null ? null : bd(n.path("value"), key);
    }

    private static BigDecimal bd(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        try { return new BigDecimal(v.asString()); } catch (NumberFormatException e) { return null; }
    }
}
