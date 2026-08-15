package de.visterion.dracul.strigoi.spin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts the spin-off distribution ratio and, best-effort, the parent ticker from a filing's
 *  plain-English information-statement summary text via regex heuristics. Stateless, fail-soft:
 *  any unparseable input yields an all-null {@link SpinTerms}, never throws. Mirrors
 *  {@code de.visterion.dracul.strigoi.merger.DealTermsParser}.
 *  The parent ticker is extracted best-effort by {@link #parentTicker} ONLY from an
 *  exchange-qualified parenthetical (e.g. "Parent Corp (NYSE: XYZ)"); a bare name yields null —
 *  no unreliable name&rarr;ticker heuristic.
 *
 *  <p><b>{@code recordDate} and {@code distributionDate} are no longer extracted here — this
 *  parser always returns {@code null} for both.</b> Four adversarial review rounds each found a
 *  new real sentence shape in which a date regex bound to the wrong date: a record-date sentence
 *  phrased with the date <em>before</em> the keyword ("held on July 20, 2026 (the record date)");
 *  a subordinate clause naming the other keyword before the date ("The record date, which
 *  precedes the distribution date, will be June 15, 2026" — this crosses any inter-keyword
 *  exclusion the gap regex can express); a table-row gap wider than any window that also excludes
 *  the sibling keyword; and {@code [^.]}, which does not stop at a line break, so an 80-character
 *  gap reaches past the sentence into an unrelated, later table cell once the extractor turns
 *  {@code <td>} into a newline. Measured on four real information statements, three of the four
 *  "distribution dates" the pre-reduction regex returned were in fact the record date — a
 *  document swap without this fix would have turned an honest abstention into a false
 *  confirmation. Each repair was locally correct and opened the next hole; that is the evidence
 *  that free SEC prose is not a regex problem for these two fields. The dates are now read by the
 *  model from the same term-sheet text, with a verbatim evidence sentence that Dracul verifies
 *  against the stored text before trusting it (design doc §4.1, §8:
 *  {@code docs/superpowers/specs/2026-08-14-spin-information-statement-design.md}). The ratio
 *  regex below is unaffected — it matches a fixed phrase shape and was never contested in any
 *  review round. */
@Component
public final class SpinTermsParser {

    private static final Logger log = LoggerFactory.getLogger(SpinTermsParser.class);

    // Cardinal words up to twelve plus bare digits/decimals — SEC info statements phrase ratios
    // both numerically ("0.25 shares") and in words ("one share ... for every three shares").
    private static final String NUM =
            "(?:one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|\\d+(?:\\.\\d+)?)";

    // "one share of NewCo common stock for every three shares" / "0.25 shares of SpinCo common
    // stock for each share" — kept as the verbatim matched phrase, not re-parsed as a ratio.
    // The trailing cardinal is optional: "for each share" has no number before "share".
    private static final Pattern DISTRIBUTION_RATIO = Pattern.compile(
            NUM + "\\s+shares?\\s+of\\s+[^.,;]{0,60}?for\\s+(?:each|every)\\s+(?:" + NUM + "\\s+)?shares?",
            Pattern.CASE_INSENSITIVE);

    // An exchange-qualified ticker parenthetical: "(NYSE: XYZ)", "(NASDAQ: ABC)",
    // "(NYSE American: DEF)", "(Nasdaq Global Select Market: GHI)". Captures the 1-5 char,
    // upper-case (optionally dotted, e.g. BRK.B) ticker. Info statements name the PARENT this
    // way because the spin-co itself is not yet trading; a bare company name (no exchange:ticker)
    // never matches -> null (fail-soft, no guessing).
    private static final Pattern EXCHANGE_TICKER = Pattern.compile(
            "\\((?:NYSE|NASDAQ|Nasdaq|NYSE American|NYSE Arca|Cboe)[^:)]*:\\s*([A-Z]{1,5}(?:\\.[A-Z])?)\\s*\\)");

    public SpinTerms parse(String termSheet) {
        if (termSheet == null || termSheet.isBlank()) {
            return new SpinTerms(null, null, null);
        }
        try {
            String ratio = findFirst(DISTRIBUTION_RATIO, termSheet, 0);
            return new SpinTerms(ratio, null, null);
        } catch (Exception e) {
            log.debug("spin terms parse failed: {}", e.getMessage());
            return new SpinTerms(null, null, null);
        }
    }

    /**
     * Best-effort parent ticker from the first exchange-qualified parenthetical in the term sheet
     * (e.g. "Parent Corp (NYSE: XYZ)" &rarr; {@code "XYZ"}). Returns null when no such
     * exchange:ticker pattern appears (a bare parent name is NOT resolved to a ticker). The caller
     * is responsible for discarding a match that equals the spin-co's own symbol.
     */
    public String parentTicker(String termSheet) {
        if (termSheet == null || termSheet.isBlank()) return null;
        try {
            Matcher m = EXCHANGE_TICKER.matcher(termSheet);
            return m.find() ? m.group(1) : null;
        } catch (RuntimeException e) {
            log.debug("spin terms: parent-ticker extraction failed: {}", e.getMessage());
            return null;
        }
    }

    private String findFirst(Pattern pattern, String text, int group) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(group) : null;
    }
}
