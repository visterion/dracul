package de.visterion.dracul.strigoi.spin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts structured spin-off distribution terms (ratio, record date, distribution date)
 *  from a filing's plain-English information-statement summary text via regex heuristics.
 *  Stateless, fail-soft: any unparseable input yields an all-null {@link SpinTerms}, never
 *  throws. Mirrors {@code de.visterion.dracul.strigoi.merger.DealTermsParser}.
 *  Parent extraction is deliberately omitted (no reliable heuristic; the filing header /
 *  companyName already identifies the issuer). */
@Component
public final class SpinTermsParser {

    private static final Logger log = LoggerFactory.getLogger(SpinTermsParser.class);

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.US).withResolverStyle(ResolverStyle.STRICT);

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

    private static final String MONTH_DATE =
            "((?:January|February|March|April|May|June|July|August|September|October|November|December)"
                    + "\\s+\\d{1,2},\\s+\\d{4})";

    // Bounded to a 80-char, single-sentence window ([^.] excludes crossing a full stop) so a
    // generic "record date"/"distribution" mention with no nearby date (e.g. proxy voting
    // boilerplate: "the record date ... will be entitled to vote at the annual meeting") never
    // grabs an unrelated date from elsewhere in the text.
    private static final Pattern RECORD_DATE =
            Pattern.compile("record date[^.]{0,80}?" + MONTH_DATE, Pattern.CASE_INSENSITIVE);
    private static final Pattern DISTRIBUTION_DATE = Pattern.compile(
            "(?:distribution(?:\\s+date)?|distributed on)[^.]{0,80}?" + MONTH_DATE, Pattern.CASE_INSENSITIVE);

    public SpinTerms parse(String termSheet) {
        if (termSheet == null || termSheet.isBlank()) {
            return new SpinTerms(null, null, null);
        }
        try {
            String ratio = findFirst(DISTRIBUTION_RATIO, termSheet, 0);
            String recordDate = parseDate(findFirst(RECORD_DATE, termSheet, 1));
            String distributionDate = parseDate(findFirst(DISTRIBUTION_DATE, termSheet, 1));
            return new SpinTerms(ratio, recordDate, distributionDate);
        } catch (Exception e) {
            log.debug("spin terms parse failed: {}", e.getMessage());
            return new SpinTerms(null, null, null);
        }
    }

    private String findFirst(Pattern pattern, String text, int group) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(group) : null;
    }

    /** Parses a "Month d, yyyy" phrase to ISO-8601; any parse failure (including calendar-invalid
     *  dates like "February 30") degrades to null rather than throwing. */
    private String parseDate(String phrase) {
        if (phrase == null) return null;
        try {
            return LocalDate.parse(phrase, DATE_FORMAT).toString();
        } catch (DateTimeParseException e) {
            log.debug("spin terms: unparseable date '{}': {}", phrase, e.getMessage());
            return null;
        }
    }
}
