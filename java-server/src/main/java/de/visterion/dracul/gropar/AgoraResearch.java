package de.visterion.dracul.gropar;

import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;

/** Fetches Agora's bundled get_indicators for gropar and maps it to a neutral ExitTa. Never throws. */
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
        args.put("period", atrPeriod);
        args.put("multiple", atrMultiple);
        args.put("maFast", maFast);
        args.put("maSlow", maSlow);
        args.put("minBars52w", minBars52w);
        JsonNode r;
        try {
            r = agora.callTool("get_indicators", args);
        } catch (AgoraUnavailableException e) {
            return ExitTa.unavailable();
        }
        return new ExitTa(
                bd(r, "atr"), r.path("atrAvailable").asBoolean(false),
                bd(r, "chandelierStop"), r.path("chandelierBreached").asBoolean(false),
                bd(r, "maFast"), r.path("maFastAvailable").asBoolean(false),
                bd(r, "maSlow"), r.path("maSlowAvailable").asBoolean(false),
                r.path("maCrossState").asString("NEUTRAL"),
                bd(r, "high52w"), bd(r, "low52w"), r.path("window52wAvailable").asBoolean(false));
    }

    private static BigDecimal bd(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        try { return new BigDecimal(v.asString()); } catch (NumberFormatException e) { return null; }
    }
}
