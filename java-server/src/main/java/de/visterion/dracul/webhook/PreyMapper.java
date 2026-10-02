package de.visterion.dracul.webhook;

import de.visterion.dracul.prey.Prey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Shared output.prey[] → List<Prey> mapping for prey-producing hunters. */
public class PreyMapper {

    private static final Logger log = LoggerFactory.getLogger(PreyMapper.class);

    /** JSON key of the structured, code-enforced kill level. The output schemas
     *  (prey-list-pead.json, prey-list-lazarus.json) declare exactly this property name. */
    public static final String KILL_CLOSE_BELOW = "kill_close_below";

    /** Upper bound for a usable {@code kill_close_below}: NUMERIC(18,6) in the `prey` table
     *  overflows well before this, so any level at or above it is dropped the same way as an
     *  unusable value rather than failing the insert and losing the whole batch. */
    private static final BigDecimal MAX_KILL_CLOSE_BELOW = new BigDecimal("1e12");

    public List<Prey> map(JsonNode preyArray, String discoveredBy,
                          String defaultAnomalyType, String defaultHorizon, boolean skipBlankSymbol) {
        var out = new ArrayList<Prey>();
        if (!preyArray.isArray()) return out;
        String now = Instant.now().toString();
        for (JsonNode p : preyArray) {
            String symbol = p.path("symbol").asText("");
            if (skipBlankSymbol && symbol.isBlank()) continue;
            var signals = new ArrayList<String>();
            for (var s : p.path("signals")) signals.add(s.asText(""));
            var risks = new ArrayList<String>();
            for (var r : p.path("risks")) risks.add(r.asText(""));
            var killCriteria = new ArrayList<String>();
            for (var k : p.path("kill_criteria")) killCriteria.add(k.asText(""));
            out.add(new Prey(
                    UUID.randomUUID().toString(), symbol,
                    p.path("companyName").asText(""),
                    p.path("anomalyType").asText(defaultAnomalyType),
                    p.path("confidence").asDouble(0.0),
                    p.path("thesis").asText(""),
                    signals, risks,
                    killCriteria,
                    p.path("horizon").asText(defaultHorizon),
                    discoveredBy, now,
                    killCloseBelow(p.path(KILL_CLOSE_BELOW), symbol)));
        }
        return out;
    }

    /**
     * The schema accepts number, string and null for {@code kill_close_below} on purpose: a schema
     * violation fails the whole Vistierie run, which would lose every prey for one bad optional
     * field. All value checks therefore live here. Only a strictly positive JSON number survives;
     * absent / JSON null mean "no level" (silently); anything else present (a string such as a
     * decimal-comma slip, zero, a negative, a boolean, an array, a non-finite or overflowing
     * number) is dropped with a WARN naming the prey symbol and the raw value.
     */
    static BigDecimal killCloseBelow(JsonNode v, String symbol) {
        if (v == null || v.isMissingNode() || v.isNull()) return null;
        if (v.isNumber() && Double.isFinite(v.asDouble())) {
            try {
                BigDecimal level = new BigDecimal(v.asString());
                if (level.signum() > 0 && level.compareTo(MAX_KILL_CLOSE_BELOW) < 0) return level;
            } catch (NumberFormatException ignored) {
                // falls through to the WARN below, same as any other unusable value
            }
        }
        log.warn("prey {}: {} ignored, unusable value {}", symbol, KILL_CLOSE_BELOW, v);
        return null;
    }
}
