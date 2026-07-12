package de.visterion.dracul.strigoi.merger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts structured merger deal terms (offer price, consideration type, exchange ratio,
 *  break fee, and the deal time-axis dates) from a filing's plain-English summary term-sheet
 *  text via regex heuristics. Stateless, fail-soft: any unparseable input yields an all-null
 *  {@link DealTerms}, never throws. Conservative by design — it prefers {@code null} over a
 *  wrong extraction, because a wrong offer price poisons the spread and a wrong date poisons
 *  the annualized-spread / unaffected-price math downstream. */
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

    /** A US long-form calendar date with three capture groups: month name, day, year.
     *  Only fully spelled-out month names (Locale.US, "March") match — abbreviations
     *  ("Sept", "Sep.") deliberately yield null. Legal recitals spell months out in full,
     *  so this is a null-over-wrong trade-off, not a gap: an unrecognized month is treated as
     *  "no date" rather than risking a misparse. */
    private static final String LONG_DATE =
            "(January|February|March|April|May|June|July|August|September|October|November|December)"
            + "\\s+(\\d{1,2}),?\\s+(\\d{4})";

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("january", 1), Map.entry("february", 2), Map.entry("march", 3),
            Map.entry("april", 4), Map.entry("may", 5), Map.entry("june", 6),
            Map.entry("july", 7), Map.entry("august", 8), Map.entry("september", 9),
            Map.entry("october", 10), Map.entry("november", 11), Map.entry("december", 12));

    // Agreement/announcement date: "Agreement and Plan of Merger, dated as of March 15, 2026",
    // "dated March 15, 2026". A merger agreement's "dated (as of) <date>" is the announcement
    // anchor. The reluctant, length-bounded gap in ENTERED_ON can never backtrack catastrophically.
    private static final Pattern DATED =
            Pattern.compile("dated\\s+(?:as of\\s+)?" + LONG_DATE, Pattern.CASE_INSENSITIVE);
    // "...entered into ... on March 15, 2026".
    private static final Pattern ENTERED_ON =
            Pattern.compile("entered into\\b.{0,80}?\\bon\\s+" + LONG_DATE,
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // Expected close, most precise first: an explicit date, then a quarter, then a half.
    private static final String CLOSE_PREFIX = "expected to (?:close|be completed|be consummated)";
    private static final Pattern CLOSE_BY_DATE =
            Pattern.compile(CLOSE_PREFIX + "\\b.{0,40}?\\b(?:by|on)\\s+" + LONG_DATE,
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CLOSE_QUARTER =
            Pattern.compile(CLOSE_PREFIX + "\\b.{0,60}?\\bin the (first|second|third|fourth) quarter of (\\d{4})",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CLOSE_HALF =
            Pattern.compile(CLOSE_PREFIX + "\\b.{0,60}?\\bin the (first|second) half of (\\d{4})",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // Outside / drop-dead / End Date — the latest permissible close, kept SEPARATE from the
    // expected close (a deal usually closes well before its outside date).
    private static final Pattern OUTSIDE_DATE =
            Pattern.compile("(?:outside date|end date|drop[- ]?dead date|termination date)\\b.{0,40}?" + LONG_DATE,
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public DealTerms parse(String termSheet) {
        if (termSheet == null || termSheet.isBlank()) {
            return new DealTerms(null, null, null, null, null, null, null);
        }
        try {
            BigDecimal offerPrice = findOfferPrice(termSheet);
            String exchangeRatio = findFirst(EXCHANGE_RATIO, termSheet);
            String breakFee = findFirst(BREAK_FEE, termSheet);
            String considerationType = considerationType(offerPrice != null, exchangeRatio != null);
            LocalDate agreementDate = findAgreementDate(termSheet);
            LocalDate expectedCloseDate = findExpectedCloseDate(termSheet);
            LocalDate outsideDate = findLongDate(OUTSIDE_DATE, termSheet);
            return new DealTerms(offerPrice, considerationType, exchangeRatio, breakFee,
                    agreementDate, expectedCloseDate, outsideDate);
        } catch (Exception e) {
            log.debug("deal terms parse failed: {}", e.getMessage());
            return new DealTerms(null, null, null, null, null, null, null);
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

    /** Prefer an explicit "dated (as of) <date>" (the standard merger-agreement recital) over
     *  "entered into ... on <date>". */
    private LocalDate findAgreementDate(String text) {
        LocalDate dated = findLongDate(DATED, text);
        return dated != null ? dated : findLongDate(ENTERED_ON, text);
    }

    /** Precedence: an explicit "by/on <date>" wins; otherwise a quarter/half maps to its
     *  conservative period END (Q1→Mar 31, Q2→Jun 30, Q3→Sep 30, Q4→Dec 31; H1→Jun 30,
     *  H2→Dec 31). An outside/End Date is never treated as an expected close. */
    private LocalDate findExpectedCloseDate(String text) {
        LocalDate byDate = findLongDate(CLOSE_BY_DATE, text);
        if (byDate != null) return byDate;
        LocalDate quarter = findQuarterEnd(text);
        if (quarter != null) return quarter;
        return findHalfEnd(text);
    }

    private LocalDate findQuarterEnd(String text) {
        Matcher m = CLOSE_QUARTER.matcher(text);
        if (!m.find()) return null;
        int year = parseYear(m.group(2));
        return switch (m.group(1).toLowerCase(Locale.ROOT)) {
            case "first" -> LocalDate.of(year, 3, 31);
            case "second" -> LocalDate.of(year, 6, 30);
            case "third" -> LocalDate.of(year, 9, 30);
            case "fourth" -> LocalDate.of(year, 12, 31);
            default -> null;
        };
    }

    private LocalDate findHalfEnd(String text) {
        Matcher m = CLOSE_HALF.matcher(text);
        if (!m.find()) return null;
        int year = parseYear(m.group(2));
        return "first".equalsIgnoreCase(m.group(1)) ? LocalDate.of(year, 6, 30) : LocalDate.of(year, 12, 31);
    }

    /** Extracts the first {@link #LONG_DATE} match of {@code pattern} (groups 1/2/3 =
     *  month/day/year). Returns null when absent or when the date is not a real calendar day
     *  (e.g. "February 30") — conservative: a malformed date is treated as no date. */
    private LocalDate findLongDate(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) return null;
        Integer month = MONTHS.get(m.group(1).toLowerCase(Locale.ROOT));
        if (month == null) return null;
        try {
            return LocalDate.of(parseYear(m.group(3)), month, Integer.parseInt(m.group(2)));
        } catch (DateTimeException | NumberFormatException e) {
            return null;
        }
    }

    private static int parseYear(String s) {
        return Integer.parseInt(s);
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
