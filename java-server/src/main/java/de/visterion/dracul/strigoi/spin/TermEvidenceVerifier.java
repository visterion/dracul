package de.visterion.dracul.strigoi.spin;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic, model-free belay against a hallucinated term-sheet date (design §4.1). The
 * agent that reads {@code EnrichedSpinCandidate.termSheet} now returns {@code recordDate}/
 * {@code distributionDate} itself, together with the verbatim sentence ({@code evidence}) it
 * read the date from. {@link StrigoiSpinWebhookController} only accepts a date when this class
 * says the evidence really occurs in the stored term-sheet text AND contains that date — a
 * hallucination cannot pass both checks, and neither check involves a model.
 *
 * <p><b>Whitespace.</b> The extracted filing text breaks lines mid-sentence (measured: a
 * newline can sit between "record" and "date" in a real document), so both the term-sheet text
 * and the evidence sentence are whitespace-normalised (any run of whitespace collapsed to a
 * single space) before comparison — otherwise a genuinely-quoted sentence would fail the
 * substring check purely because of where EDGAR's HTML happened to wrap a line.
 *
 * <p><b>Date spelling.</b> The model is instructed to return ISO-8601 ({@code 2026-03-02}), but
 * the filing prose itself usually spells the date out ("March 2, 2026"). The evidence sentence
 * is checked for EITHER spelling — requiring the ISO form literally inside SEC prose would
 * reject every honest reading.
 */
public final class TermEvidenceVerifier {

    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final DateTimeFormatter LONG_FORM = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US);

    private TermEvidenceVerifier() {}

    /**
     * True when {@code evidence} (after whitespace normalisation) occurs verbatim inside
     * {@code termSheetText} (after the same normalisation) AND that evidence sentence contains
     * {@code isoDate} — either as the ISO-8601 string itself or spelled out ("March 2, 2026").
     * False whenever any input is null/blank, when the evidence cannot be located in the term
     * sheet, or when the date is missing from the evidence.
     */
    public static boolean supports(String termSheetText, String evidence, String isoDate) {
        if (isBlank(termSheetText) || isBlank(evidence) || isBlank(isoDate)) return false;
        if (!ISO_DATE.matcher(isoDate.trim()).matches()) return false;

        String normalizedText = normalizeWhitespace(termSheetText);
        String normalizedEvidence = normalizeWhitespace(evidence);
        if (normalizedEvidence.isEmpty() || !normalizedText.contains(normalizedEvidence)) return false;

        return containsDate(normalizedEvidence, isoDate.trim());
    }

    private static boolean containsDate(String normalizedEvidence, String isoDate) {
        if (normalizedEvidence.contains(isoDate)) return true;
        String longForm = longForm(isoDate);
        return longForm != null && normalizedEvidence.contains(longForm);
    }

    private static String longForm(String isoDate) {
        try {
            return LocalDate.parse(isoDate).format(LONG_FORM);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String normalizeWhitespace(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
