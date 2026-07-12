package de.visterion.dracul.hunting.agora;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the SEC registrant CIK from an EDGAR archive filing URL of the shape
 * {@code .../Archives/edgar/data/<CIK>/<accession>/<document>}.
 *
 * <p>Verified against Agora's {@code EdgarSearchService.parseHit}: the archive-path
 * CIK is taken from {@code _source.ciks[0]} (the filing registrant), NOT the
 * accession prefix (the filing-agent CIK), and its leading zeros are stripped via
 * {@code Long.parseLong} ({@code /data/320193/}, not {@code /data/0000320193/}).
 * For a Form 10-12B this registrant is the spin-co itself, so the URL CIK is the
 * spin-co's own CIK.
 *
 * <p>This helper normalises the stripped value back to the canonical 10-digit
 * zero-padded form used across the ecosystem (cf. Agora {@code EdgarService} /
 * {@code EdgarCikResolver} {@code "%010d"}), so it lines up with the
 * {@code COALESCE(cik, lower(company_name))} natural key. Fail-soft: a null/blank
 * URL, or one without a parseable {@code /data/<digits>/} segment, yields null.
 */
public final class CikExtractor {

    private static final Pattern DATA_CIK =
            Pattern.compile("/Archives/edgar/data/(\\d{1,10})(?:/|$)");

    private CikExtractor() {}

    /** The zero-padded 10-digit registrant CIK, or null when the URL carries none. */
    public static String fromFilingUrl(String url) {
        if (url == null || url.isBlank()) return null;
        Matcher m = DATA_CIK.matcher(url);
        if (!m.find()) return null;
        try {
            return String.format("%010d", Long.parseLong(m.group(1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
