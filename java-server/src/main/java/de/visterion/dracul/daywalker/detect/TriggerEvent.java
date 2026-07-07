package de.visterion.dracul.daywalker.detect;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One deterministically-detected trigger on a watchlist symbol. {@code detail}
 * carries trigger-specific figures the LLM uses to assess severity. When the ticker
 * is HELD by an owner, {@code positionId}/{@code position}/{@code breachedLevel} carry
 * that position's pre-set exit levels; they are null for watch-only tickers.
 */
public record TriggerEvent(
        String symbol,
        String companyName,
        TriggerType triggerType,
        BigDecimal currentPrice,
        Map<String, Object> detail,
        String positionId,
        PositionContext position,
        String breachedLevel) {

    /** Convenience factory for a watch-only trigger (no position context). */
    public static TriggerEvent watchOnly(String symbol, String companyName,
            TriggerType type, BigDecimal price, Map<String, Object> detail) {
        return new TriggerEvent(symbol, companyName, type, price, detail, null, null, null);
    }

    /** Snake-case wire form forwarded to the child run as its payload. */
    public Map<String, Object> toEventPayload() {
        var m = new LinkedHashMap<String, Object>();
        m.put("symbol", symbol);
        m.put("company_name", companyName);
        m.put("trigger_type", triggerType.name());
        m.put("current_price", currentPrice);
        m.put("detail", detail);
        if (positionId != null) m.put("position_id", positionId);
        if (position != null) m.put("position", position.toMap());
        if (breachedLevel != null) m.put("breached_level", breachedLevel);
        return m;
    }
}
