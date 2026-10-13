package de.visterion.dracul.benchmark;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Quote currency of an Agora/Yahoo symbol from its suffix (spec 2026-10-13 §3.3). No candidate
 * carries a currency field, so the suffix is the only source. {@code .L} is deliberately unknown
 * (plan ruling R1: Yahoo quotes it in pence and no real .L bar was verified). Unknown means the
 * event is skipped and counted, never guessed.
 */
public final class CurrencyResolver {

    public static final String EUR = "EUR";

    private static final Set<String> EUR_SUFFIXES =
            Set.of("DE", "F", "AS", "PA", "MI", "MC", "BR", "VI", "HE", "LS");

    private CurrencyResolver() {
    }

    public static Optional<String> currencyOf(String symbol) {
        if (symbol == null || symbol.isBlank()) return Optional.empty();
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        if (s.endsWith("=X")) return Optional.empty();
        int dot = s.lastIndexOf('.');
        if (dot < 0) return Optional.of("USD");
        String suffix = s.substring(dot + 1);
        if (EUR_SUFFIXES.contains(suffix)) return Optional.of(EUR);
        if ("HK".equals(suffix)) return Optional.of("HKD");
        return Optional.empty();
    }

    /** Agora's EUR cross for {@code currency}, e.g. {@code USDEUR=X} (verified 2026-10-13). */
    public static String fxPair(String currency) {
        return currency + "EUR=X";
    }
}
