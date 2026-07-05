package de.visterion.dracul.marketdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.ConcurrentHashMap;

/** Currency conversion for display. Rates are fetched from Agora (get_fx_rate) by the
 *  background refresher via {@link #warm}; {@link #convert} is cache-only and never fetches. */
@Service
public class FxService {

    private static final Logger log = LoggerFactory.getLogger(FxService.class);

    private final AgoraClient agora;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, BigDecimal> cache = new ConcurrentHashMap<>();

    public FxService(AgoraClient agora) {
        this.agora = agora;
    }

    /** Convert from -> to using only cached rates; never performs a live fetch.
     *  Identity on null amount, same currency, or a cache miss. */
    public BigDecimal convert(BigDecimal amount, String from, String to) {
        if (amount == null) return null;
        if (from == null || to == null || from.equalsIgnoreCase(to)) return amount;
        BigDecimal rate = cache.get(pair(from, to));
        if (rate == null) return amount; // not yet warmed: serve unconverted rather than block
        return amount.multiply(rate).setScale(4, RoundingMode.HALF_UP);
    }

    /** Fetch the latest from -> to rate from Agora and cache it. Called by the background refresher.
     *  Never throws; on failure logs and keeps the last-known rate. */
    public void warm(String from, String to) {
        if (from == null || to == null || from.equalsIgnoreCase(to)) return;
        String pair = pair(from, to);
        try {
            ObjectNode args = mapper.createObjectNode();
            args.put("from", from);
            args.put("to", to);
            JsonNode body = agora.callTool("get_fx_rate", args);
            String px = body.path("rate").asString(null);
            if (px == null) throw new IllegalStateException("no rate for " + pair);
            cache.put(pair, new BigDecimal(px));
        } catch (Exception e) {
            log.warn("FX warm failed for {}: {} — keeping last-known", pair, e.toString());
        }
    }

    private static String pair(String from, String to) {
        return from.toUpperCase() + to.toUpperCase() + "=X";
    }
}
