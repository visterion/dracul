package de.visterion.dracul.benchmark;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;

/**
 * Maps ONE raw fetch item — the JSON a hunter's fetch tool returned — to a {@link ScreenItem}.
 * Live capture serialises the hunter's records with the app ObjectMapper first, the backfill
 * import receives the same JSON from Vistierie's run_tool_calls: one mapping for both (plan
 * ruling R3). Field names are the item record's component names.
 */
public record ScreenItemMapper(String agent, String symbolField, String scoreField, boolean captureOffer) {

    public Optional<ScreenItem> map(JsonNode item) {
        if (item == null || !item.isObject()) return Optional.empty();
        Optional<String> symbol = SymbolExtractor.extract(text(item, symbolField), text(item, "companyName"));
        if (symbol.isEmpty()) return Optional.empty();
        return Optional.of(new ScreenItem(symbol.get(), decimal(item, scoreField),
                captureOffer ? offer(item) : null));
    }

    private static ObjectNode offer(JsonNode item) {
        BigDecimal price = decimal(item, "offerPrice");
        if (price == null) return null;
        ObjectNode extra = JsonNodeFactory.instance.objectNode();
        extra.put("offerPrice", price);
        String currency = text(item, "offerCurrency");
        if (currency != null && !currency.isBlank()) {
            extra.put("offerCurrency", currency.trim().toUpperCase(Locale.ROOT));
        }
        return extra;
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isTextual() ? v.asString() : null;
    }

    static BigDecimal decimal(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isNumber()) {
            double d = v.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) return null;
            return v.decimalValue();
        }
        if (v.isTextual()) {
            try {
                return new BigDecimal(v.asString().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
