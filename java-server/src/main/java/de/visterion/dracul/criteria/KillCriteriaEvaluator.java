package de.visterion.dracul.criteria;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, best-effort evaluation of free-text kill criteria.
 * v1 understands only absolute price levels ("close below $90", "rises above 120").
 * Percent thresholds and qualitative criteria are ignored (left to the LLM).
 */
@Component
public final class KillCriteriaEvaluator {

    // The number capture must not lead into a percent threshold ("above 12%", "above 12.5 %"):
    // the lookahead rejects when the remaining digits/decimals run into a '%', which also blocks
    // backtracking tricks like matching "12" out of "125%".
    private static final Pattern BELOW = Pattern.compile(
            "(?:close[sd]?|closing|price|trade[sd]?|fall[s]?|drop[s]?|break[s]?|stop[s]?)\\b[^%\\d]{0,40}?" +
            "\\b(?:below|under|beneath)\\s*\\$?(\\d+(?:\\.\\d+)?)(?![\\d.]*\\s*%)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ABOVE = Pattern.compile(
            "(?:close[sd]?|closing|price|trade[sd]?|rise[s]?|rally|break[s]?|move[s]?)\\b[^%\\d]{0,40}?" +
            "\\b(?:above|over|exceed[s]?|beyond)\\s*\\$?(\\d+(?:\\.\\d+)?)(?![\\d.]*\\s*%)",
            Pattern.CASE_INSENSITIVE);

    public List<String> breached(List<String> criteria, BigDecimal close) {
        List<String> out = new ArrayList<>();
        if (criteria == null || close == null) return out;
        for (String c : criteria) {
            if (c == null || c.isBlank()) continue;
            try {
                Matcher below = BELOW.matcher(c);
                if (below.find() && close.compareTo(new BigDecimal(below.group(1))) < 0) {
                    out.add(c);
                    continue;
                }
                Matcher above = ABOVE.matcher(c);
                if (above.find() && close.compareTo(new BigDecimal(above.group(1))) > 0) {
                    out.add(c);
                }
            } catch (RuntimeException ignored) {
                // unparseable criterion — leave to the LLM
            }
        }
        return out;
    }
}
