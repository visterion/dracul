package de.visterion.dracul.benchmark;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one symbol extraction shared by live capture and the backfill import (spec 2026-10-13 §2.2).
 *
 * <p>{@code symbol} (or {@code ticker} — the caller passes whichever field the hunter uses) wins when
 * non-blank. Otherwise the ticker is the FIRST comma-separated token of the FIRST parenthesised
 * group of {@code companyName}: EDGAR display names read
 * {@code "Synthetic Storage Trust  (SYNA, SYNA-PA, SYNA-PB)  (CIK 0000000001)"}. The token must be
 * complete — terminated by {@code ,} or {@code )} — so {@code "(CIK 0000000001)"} and
 * {@code "(Delaware)"} never yield {@code CIK} or {@code D} (plan ruling R2).
 */
public final class SymbolExtractor {

    private static final Pattern FIRST_TICKER = Pattern.compile("^\\(\\s*([A-Z][A-Z0-9.\\-]*)\\s*[,)]");

    private SymbolExtractor() {
    }

    public static Optional<String> extract(String symbol, String companyName) {
        if (symbol != null && !symbol.isBlank()) {
            return Optional.of(symbol.trim().toUpperCase(Locale.ROOT));
        }
        if (companyName == null) return Optional.empty();
        int open = companyName.indexOf('(');
        if (open < 0) return Optional.empty();
        Matcher m = FIRST_TICKER.matcher(companyName.substring(open));
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }
}
