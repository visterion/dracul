package de.visterion.dracul.daywalker.detect;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** The held position's pre-set exit levels, forwarded so the LLM judges the event
 *  against them instead of abstract percentages. All fields nullable. */
public record PositionContext(
        BigDecimal entry,
        BigDecimal gainLossPct,
        BigDecimal activeStop,
        BigDecimal nextTarget,
        BigDecimal atr,
        BigDecimal distToStopInAtr) {

    /** Snake-case wire form for the event payload. */
    public Map<String, Object> toMap() {
        var m = new LinkedHashMap<String, Object>();
        m.put("entry", entry);
        m.put("gain_loss_pct", gainLossPct);
        m.put("active_stop", activeStop);
        m.put("next_target", nextTarget);
        m.put("atr", atr);
        m.put("dist_to_stop_in_atr", distToStopInAtr);
        return m;
    }
}
