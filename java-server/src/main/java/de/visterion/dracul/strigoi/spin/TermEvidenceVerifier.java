package de.visterion.dracul.strigoi.spin;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, model-free belay against a hallucinated term-sheet date (design §4.1, tightened
 * after the D5 fix-round-1 review). The agent that reads {@code EnrichedSpinCandidate.termSheet}
 * returns {@code recordDate}/{@code distributionDate} itself, together with the verbatim sentence
 * ({@code evidence}) it read the date from. {@link StrigoiSpinWebhookController} only accepts a
 * date when this class says so.
 *
 * <p><b>Why five rules, not two.</b> "The evidence occurs in the text and contains the date" is
 * too weak: it accepted a RECORD date submitted as {@code distributionDate} whenever the quoted
 * sentence mentioned BOTH dates ("The record date is March 2, 2026 and the distribution date is
 * March 16, 2026") — exactly the cross-binding failure mode this whole feature exists to stop —
 * and it accepted a bare date with no sentence around it at all.
 *
 * <p><b>All five rules must hold</b> (an accepted-but-wrong date opens the promotion window; a
 * wrongly-rejected date only costs one missing field — the asymmetry is why this is strict):
 * <ol>
 *   <li>The evidence is at least {@value #MIN_EVIDENCE_LENGTH} characters — a bare date is not a
 *       sentence.</li>
 *   <li>The evidence occurs verbatim in the term-sheet text, whitespace-normalised (collapsing any
 *       run of whitespace INCLUDING {@code &nbsp;}/U+00A0, which is pervasive in SEC HTML and is
 *       NOT matched by {@code \s}) and compared case-insensitively (SEC cover pages are often
 *       ALL CAPS).</li>
 *   <li>The evidence contains EXACTLY ONE date. Two dates in the same sentence is the exact
 *       cross-binding shape above — ambiguous, rejected.</li>
 *   <li>That one date equals the date the model reported.</li>
 *   <li>The evidence carries the FULL keyword of the field being verified ({@code record\s+date}
 *       for {@link Field#RECORD_DATE}, {@code distribution\s+date} or {@code distributed} for
 *       {@link Field#DISTRIBUTION_DATE}) and does NOT carry the full keyword of the OTHER field.
 *       "Full keyword", not the bare word: "The record date for the <b>distribution</b> will be
 *       July 20, 2026" passes as a record date (no "distribution date"/"distributed" substring);
 *       "…precede the <b>distribution date</b> of April 1, 2026" does not pass as a record date.</li>
 * </ol>
 *
 * <p><b>Date spelling.</b> The model is instructed to return ISO-8601, but SEC prose spells dates
 * out in several ways — zero-padded ("March 02, 2026"), all caps ("MARCH 2, 2026"), abbreviated
 * with a period ("Mar. 2, 2026"), without a comma ("March 2 2026"), or day-before-month ("2 March
 * 2026"). All of these are recognised, alongside the literal ISO string and "March 2, 2026".
 */
public final class TermEvidenceVerifier {

    /** Which field a reading is being verified for — the two keyword sets in rule 5. */
    public enum Field {
        RECORD_DATE, DISTRIBUTION_DATE
    }

    private static final int MIN_EVIDENCE_LENGTH = 30;

    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /** Any run of whitespace, PLUS the non-breaking space family that {@code \s} does not cover. */
    private static final Pattern WHITESPACE_RUN = Pattern.compile("[\\s\\u00A0\\u2007\\u202F]+");

    private static final String MONTH_ALT =
            "January|Jan\\.?|February|Feb\\.?|March|Mar\\.?|April|Apr\\.?|May|June|Jun\\.?|"
                    + "July|Jul\\.?|August|Aug\\.?|September|Sept\\.?|Sep\\.?|October|Oct\\.?|"
                    + "November|Nov\\.?|December|Dec\\.?";

    private static final Pattern MONTH_DAY_YEAR = Pattern.compile(
            "\\b(" + MONTH_ALT + ")\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DAY_MONTH_YEAR = Pattern.compile(
            "\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(" + MONTH_ALT + ")\\s+(\\d{4})\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ISO_DATE_IN_TEXT = Pattern.compile("\\b(\\d{4}-\\d{2}-\\d{2})\\b");

    private static final Map<String, Integer> MONTH_NUMBERS = Map.ofEntries(
            Map.entry("jan", 1), Map.entry("feb", 2), Map.entry("mar", 3), Map.entry("apr", 4),
            Map.entry("may", 5), Map.entry("jun", 6), Map.entry("jul", 7), Map.entry("aug", 8),
            Map.entry("sep", 9), Map.entry("oct", 10), Map.entry("nov", 11), Map.entry("dec", 12));

    private static final Pattern RECORD_DATE_KEYWORD =
            Pattern.compile("record\\s+date", Pattern.CASE_INSENSITIVE);
    private static final Pattern DISTRIBUTION_DATE_KEYWORD =
            Pattern.compile("distribution\\s+date|distributed", Pattern.CASE_INSENSITIVE);

    private TermEvidenceVerifier() {}

    /**
     * True only when ALL FIVE rules in the class javadoc hold for {@code evidence} against
     * {@code termSheetText}, {@code isoDate} and {@code field}. False whenever any input is
     * null/blank, {@code isoDate} is not a real calendar date, or any single rule fails.
     */
    public static boolean supports(String termSheetText, String evidence, String isoDate, Field field) {
        if (isBlank(termSheetText) || isBlank(evidence) || isBlank(isoDate) || field == null) return false;
        LocalDate submitted = parseIso(isoDate.trim());
        if (submitted == null) return false;

        String normalizedEvidence = normalizeWhitespace(evidence);
        if (normalizedEvidence.length() < MIN_EVIDENCE_LENGTH) return false; // rule 1

        String normalizedText = normalizeWhitespace(termSheetText);
        if (!containsCaseInsensitive(normalizedText, normalizedEvidence)) return false; // rule 2

        Set<LocalDate> datesInEvidence = extractDates(normalizedEvidence);
        if (datesInEvidence.size() != 1) return false; // rule 3
        if (!datesInEvidence.contains(submitted)) return false; // rule 4

        Pattern ownKeyword = field == Field.RECORD_DATE ? RECORD_DATE_KEYWORD : DISTRIBUTION_DATE_KEYWORD;
        Pattern otherKeyword = field == Field.RECORD_DATE ? DISTRIBUTION_DATE_KEYWORD : RECORD_DATE_KEYWORD;
        return ownKeyword.matcher(normalizedEvidence).find()
                && !otherKeyword.matcher(normalizedEvidence).find(); // rule 5
    }

    /** Every distinct calendar date mentioned in {@code text}, in any of the recognised spellings
     *  (ISO, "Month D[,] YYYY" in any case/abbreviation/padding, or "D Month YYYY"). A malformed
     *  calendar date (e.g. day 30 in a month/day combination that does not exist) is silently
     *  skipped rather than thrown — it is simply not counted as a date. */
    private static Set<LocalDate> extractDates(String text) {
        Set<LocalDate> dates = new LinkedHashSet<>();
        Matcher monthDayYear = MONTH_DAY_YEAR.matcher(text);
        while (monthDayYear.find()) {
            addDate(dates, monthNumber(monthDayYear.group(1)),
                    Integer.parseInt(monthDayYear.group(2)), Integer.parseInt(monthDayYear.group(3)));
        }
        Matcher dayMonthYear = DAY_MONTH_YEAR.matcher(text);
        while (dayMonthYear.find()) {
            addDate(dates, monthNumber(dayMonthYear.group(2)),
                    Integer.parseInt(dayMonthYear.group(1)), Integer.parseInt(dayMonthYear.group(3)));
        }
        Matcher iso = ISO_DATE_IN_TEXT.matcher(text);
        while (iso.find()) {
            LocalDate d = parseIso(iso.group(1));
            if (d != null) dates.add(d);
        }
        return dates;
    }

    private static void addDate(Set<LocalDate> dates, Integer month, int day, int year) {
        if (month == null) return;
        try {
            dates.add(LocalDate.of(year, month, day));
        } catch (DateTimeException e) {
            // Not a real calendar date -- not counted, not thrown.
        }
    }

    private static Integer monthNumber(String token) {
        String key = token.toLowerCase(Locale.ROOT).replace(".", "");
        if (key.length() > 3) key = key.substring(0, 3);
        return MONTH_NUMBERS.get(key);
    }

    private static LocalDate parseIso(String s) {
        if (!ISO_DATE.matcher(s).matches()) return null;
        try {
            return LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeException e) {
            return null; // syntactically ISO-shaped but not a real calendar date (e.g. 2026-02-30)
        }
    }

    private static boolean containsCaseInsensitive(String haystack, String needle) {
        return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private static String normalizeWhitespace(String s) {
        return WHITESPACE_RUN.matcher(s).replaceAll(" ").trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
