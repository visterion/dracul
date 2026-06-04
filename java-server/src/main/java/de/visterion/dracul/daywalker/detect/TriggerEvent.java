package de.visterion.dracul.daywalker.detect;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One deterministically-detected trigger on a watchlist symbol. {@code detail}
 * carries trigger-specific figures the LLM uses to assess severity.
 */
public record TriggerEvent(
        String symbol,
        String companyName,
        TriggerType triggerType,
        BigDecimal currentPrice,
        Map<String, Object> detail) {

    /** Snake-case wire form forwarded to the child run as its payload. */
    public Map<String, Object> toEventPayload() {
        var m = new LinkedHashMap<String, Object>();
        m.put("symbol", symbol);
        m.put("company_name", companyName);
        m.put("trigger_type", triggerType.name());
        m.put("current_price", currentPrice);
        m.put("detail", detail);
        return m;
    }
}
