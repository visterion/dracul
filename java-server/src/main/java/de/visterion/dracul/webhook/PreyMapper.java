package de.visterion.dracul.webhook;

import de.visterion.dracul.prey.Prey;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Shared output.prey[] → List<Prey> mapping for prey-producing hunters. */
public class PreyMapper {

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
            out.add(new Prey(
                    UUID.randomUUID().toString(), symbol,
                    p.path("companyName").asText(""),
                    p.path("anomalyType").asText(defaultAnomalyType),
                    p.path("confidence").asDouble(0.0),
                    p.path("thesis").asText(""),
                    signals, risks,
                    p.path("horizon").asText(defaultHorizon),
                    discoveredBy, now));
        }
        return out;
    }
}
