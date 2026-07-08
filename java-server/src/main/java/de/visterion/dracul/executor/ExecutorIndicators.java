package de.visterion.dracul.executor;

import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;

/** Fetches ATR, swing low (lowest close), and reference price via Agora get_indicators. */
@Component
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class ExecutorIndicators {

    public record Levels(boolean available, BigDecimal atr, BigDecimal swingLow, BigDecimal referencePrice) {
        static Levels unavailable() { return new Levels(false, null, null, null); }
    }

    private final AgoraClient agora;
    private final ObjectMapper mapper;

    public ExecutorIndicators(AgoraClient agora, ObjectMapper mapper) {
        this.agora = agora;
        this.mapper = mapper;
    }

    public Levels levels(String symbol, int atrPeriod, int swingPeriod) {
        ObjectNode args = mapper.createObjectNode();
        args.put("symbol", symbol);
        ArrayNode indicators = args.putArray("indicators");
        ObjectNode atr = indicators.addObject();
        atr.put("name", "atr");
        atr.putObject("params").put("period", atrPeriod);
        ObjectNode low = indicators.addObject();
        low.put("name", "lowest");
        low.putObject("params").put("period", swingPeriod);
        low.put("label", "swing_low");

        JsonNode r;
        try {
            r = agora.callTool("get_indicators", args);
        } catch (AgoraUnavailableException e) {
            return Levels.unavailable();
        }

        BigDecimal atrValue = null, swingLow = null;
        for (JsonNode v : r.path("values")) {
            String label = v.path("label").asString("");
            if (!v.path("available").asBoolean(false)) continue;
            BigDecimal value = bd(v, "value");
            if (label.equals("atr")) atrValue = value;
            else if (label.equals("swing_low")) swingLow = value;
        }
        BigDecimal ref = bd(r, "currentClose");
        boolean available = atrValue != null && ref != null;
        return new Levels(available, atrValue, swingLow, ref);
    }

    private static BigDecimal bd(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        try { return new BigDecimal(v.asString()); } catch (NumberFormatException e) { return null; }
    }
}
