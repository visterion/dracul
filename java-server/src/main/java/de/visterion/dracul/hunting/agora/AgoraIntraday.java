package de.visterion.dracul.hunting.agora;

import de.visterion.dracul.hunting.yahoo.IntradayCandles;
import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Intraday-candles facade backed by Agora (get_intraday over MCP; 5-minute bars over the
 * current day, oldest-first). Never throws: any failure degrades to empty candles — the
 * Daywalker poll must never die on a data hiccup.
 */
@Component
public class AgoraIntraday {

    private static final IntradayCandles EMPTY = new IntradayCandles(List.of(), List.of());

    private final AgoraClient agora;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgoraIntraday(AgoraClient agora) { this.agora = agora; }

    /** 5-minute candles for the current day; empty candles on any failure. */
    public IntradayCandles candles(String symbol) {
        JsonNode res;
        try {
            ObjectNode args = mapper.createObjectNode();
            args.put("symbol", symbol).put("interval", "5m").put("range", "1d");
            res = agora.callTool("get_intraday", args);
        } catch (AgoraUnavailableException e) {
            return EMPTY;
        }
        List<BigDecimal> closes = new ArrayList<>();
        List<Long> volumes = new ArrayList<>();
        for (JsonNode b : res.path("bars")) {
            try {
                JsonNode close = b.path("close");
                JsonNode volume = b.path("volume");
                if (close.isMissingNode() || close.isNull()) continue;
                if (volume.isMissingNode() || volume.isNull()) continue;
                closes.add(new BigDecimal(close.asString()));
                volumes.add(volume.asLong());
            } catch (RuntimeException ignored) { /* skip malformed bar */ }
        }
        return new IntradayCandles(closes, volumes);
    }
}
