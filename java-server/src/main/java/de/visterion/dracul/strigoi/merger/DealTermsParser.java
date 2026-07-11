package de.visterion.dracul.strigoi.merger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts structured merger deal terms (offer price, consideration type, exchange ratio,
 *  break fee) from a filing's plain-English summary term-sheet text via regex heuristics.
 *  Stateless, fail-soft: any unparseable input yields an all-null {@link DealTerms}, never throws. */
@Component
public final class DealTermsParser {

    private static final Logger log = LoggerFactory.getLogger(DealTermsParser.class);

    // "$54.20 in cash", "$54.20 per share", "$54.20 for each share" — anchored to a fixed
    // set of literal trailing phrases (no unbounded lookahead) to avoid catastrophic backtracking.
    private static final Pattern OFFER_PRICE =
            Pattern.compile("\\$\\s*(\\d+(?:\\.\\d+)?)\\s*(?:in cash\\b|per share\\b|for each share\\b)",
                    Pattern.CASE_INSENSITIVE);

    // "0.5230 shares of" — kept as the verbatim matched string, not re-parsed as a number.
    private static final Pattern EXCHANGE_RATIO =
            Pattern.compile("(\\d+\\.\\d+)\\s*shares?\\s+of", Pattern.CASE_INSENSITIVE);

    // "termination fee of approximately $85 million" — group 1 kept verbatim.
    private static final Pattern BREAK_FEE =
            Pattern.compile("termination fee of (?:approximately\\s*)?(\\$[\\d.,]+\\s*(?:million|billion)?)",
                    Pattern.CASE_INSENSITIVE);

    public DealTerms parse(String termSheet) {
        if (termSheet == null || termSheet.isBlank()) {
            return new DealTerms(null, null, null, null);
        }
        try {
            BigDecimal offerPrice = findOfferPrice(termSheet);
            String exchangeRatio = findFirst(EXCHANGE_RATIO, termSheet);
            String breakFee = findFirst(BREAK_FEE, termSheet);
            String considerationType = considerationType(offerPrice != null, exchangeRatio != null);
            return new DealTerms(offerPrice, considerationType, exchangeRatio, breakFee);
        } catch (Exception e) {
            log.debug("deal terms parse failed: {}", e.getMessage());
            return new DealTerms(null, null, null, null);
        }
    }

    /** Chars of context inspected around a candidate offer-price match for par-value boilerplate. */
    private static final int PAR_VALUE_WINDOW = 40;

    private BigDecimal findOfferPrice(String text) {
        Matcher m = OFFER_PRICE.matcher(text);
        while (m.find()) {
            // DEFM14A boilerplate like "common stock, par value of $0.001 per share" would
            // otherwise match first and poison the spread — skip par-value contexts and keep
            // scanning for the real offer price.
            if (nearParValue(text, m.start(), m.end())) continue;
            try {
                return new BigDecimal(m.group(1));
            } catch (NumberFormatException e) {
                // malformed number in this candidate; try the next match
            }
        }
        return null;
    }

    private boolean nearParValue(String text, int start, int end) {
        int from = Math.max(0, start - PAR_VALUE_WINDOW);
        int to = Math.min(text.length(), end + PAR_VALUE_WINDOW);
        return text.substring(from, to).toLowerCase().contains("par value");
    }

    private String findFirst(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private String considerationType(boolean hasCash, boolean hasRatio) {
        if (hasCash && hasRatio) return "mixed";
        if (hasCash) return "cash";
        if (hasRatio) return "stock";
        return null;
    }
}
