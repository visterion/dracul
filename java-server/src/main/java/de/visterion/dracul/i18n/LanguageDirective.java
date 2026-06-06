package de.visterion.dracul.i18n;

/**
 * Appends a target-language directive to a (language-neutral) base system prompt.
 * Policy: the configured language wins for narrative output; verbatim source terms
 * (filing titles, tickers, company names, quotes) stay raw.
 */
public final class LanguageDirective {

    private LanguageDirective() {}

    public static String append(String basePrompt, String language) {
        String name = displayName(language);
        return basePrompt + """


                ## Output language

                IMPORTANT: Write all narrative output (summaries, theses, signals, risks) in %s.
                Keep verbatim source quotes, filing titles, tickers and company names in their
                original language — do not translate them.""".formatted(name);
    }

    private static String displayName(String language) {
        if (language == null) return "German";
        return switch (language.strip().toLowerCase()) {
            case "en" -> "English";
            case "de" -> "German";
            default -> "German";
        };
    }
}
