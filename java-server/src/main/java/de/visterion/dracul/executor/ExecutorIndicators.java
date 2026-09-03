package de.visterion.dracul.executor;

import de.visterion.dracul.marketdata.AgoraClient;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import org.springframework.beans.factory.annotation.Value;
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

    /**
     * @param atrShort the short-period ATR (label {@code atr_short}), nullable — a symbol with too
     *        few bars simply has none, which must NOT make the bundle unavailable (see
     *        {@code available} below). Appended last so existing positional constructions of this
     *        record only gain one argument.
     */
    public record Levels(boolean available, BigDecimal atr, BigDecimal swingLow,
            BigDecimal referencePrice, BigDecimal atrShort) {
        static Levels unavailable() { return new Levels(false, null, null, null, null); }

        /**
         * The ATR every stop-distance decision uses: the WIDER of the long and short window.
         * A short ATR that is smaller than ATR22 never narrows anything — post-report volatility
         * can only widen the stop, never tighten it.
         */
        public BigDecimal atrEff() {
            if (atr == null) return atrShort;
            if (atrShort == null) return atr;
            return atr.max(atrShort);
        }
    }

    private final AgoraClient agora;
    private final ObjectMapper mapper;
    private final int atrShortPeriod;

    // @Autowired is REQUIRED here: the class now has two constructors, and without an explicit
    // marker Spring refuses to pick one.
    @org.springframework.beans.factory.annotation.Autowired
    public ExecutorIndicators(AgoraClient agora, ObjectMapper mapper,
            @Value("${dracul.executor.atr-short-period:5}") int atrShortPeriod) {
        this.agora = agora;
        this.mapper = mapper;
        this.atrShortPeriod = atrShortPeriod;
    }

    /** Convenience for tests and callers that do not care about the short ATR period. */
    public ExecutorIndicators(AgoraClient agora, ObjectMapper mapper) {
        this(agora, mapper, 5);
    }

    public Levels levels(String symbol, int atrPeriod, int swingPeriod) {
        ObjectNode args = mapper.createObjectNode();
        args.put("symbol", symbol);
        ArrayNode indicators = args.putArray("indicators");
        ObjectNode atr = indicators.addObject();
        atr.put("name", "atr");
        atr.putObject("params").put("period", atrPeriod);
        // The SHORT ATR must carry an explicit label. Agora derives a default label from the
        // indicator NAME only (IndicatorExpressionResolver:112-113), so two unlabelled "atr"
        // specs would both be called "atr", and Agora rejects duplicate labels with
        // available:false (IndicatorEvaluator:61-64) -- the short ATR would silently never
        // arrive. The LONG one keeps the default label, which is what "atr" already means to
        // every existing consumer.
        ObjectNode atrShortSpec = indicators.addObject();
        atrShortSpec.put("name", "atr");
        atrShortSpec.putObject("params").put("period", atrShortPeriod);
        atrShortSpec.put("label", "atr_short");
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

        BigDecimal atrValue = null, atrShort = null, swingLow = null;
        for (JsonNode v : r.path("values")) {
            String label = v.path("label").asString("");
            if (!v.path("available").asBoolean(false)) continue;
            BigDecimal value = bd(v, "value");
            if (label.equals("atr")) atrValue = value;
            else if (label.equals("atr_short")) atrShort = value;
            else if (label.equals("swing_low")) swingLow = value;
        }
        BigDecimal ref = bd(r, "currentClose");
        // atrShort deliberately does NOT participate: a symbol with too few bars for the short
        // window would otherwise drop out of closeBySymbol and lose hard trigger AND ratchet for
        // the whole run.
        boolean available = atrValue != null && ref != null;
        return new Levels(available, atrValue, swingLow, ref, atrShort);
    }

    private static BigDecimal bd(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        try { return new BigDecimal(v.asString()); } catch (NumberFormatException e) { return null; }
    }
}
