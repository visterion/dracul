package de.visterion.dracul.strigoi.lazarus;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Classifies a ticker as US or non-US by its venue suffix, mirroring Agora's
 * {@code Instrument.classify}: take the substring after the LAST {@code '.'}; a symbol whose
 * suffix is in the configured venue whitelist is non-US, everything else (including
 * class-share tickers like {@code BRK.B}/{@code BF.B} whose "suffix" is not a venue, and bare
 * US tickers with no dot) is US.
 *
 * <p>The whitelist comes from {@code dracul.fundamentals.non-us-suffixes} (comma-separated),
 * so a new venue can be onboarded without a code change. The classification decides which
 * fundamentals path {@link AltmanZCalculator} takes: US keeps the byte-identical us-gaap
 * {@code get_company_facts} route; non-US uses the currency-aware {@code get_fundamental_concepts}
 * route.
 */
@Component
public class InstrumentClassifier {

    private final Set<String> nonUsSuffixes;

    public InstrumentClassifier(
            @Value("${dracul.fundamentals.non-us-suffixes:DE,MI,TO,L,T,HK,PA,AS,SW,AX,ST,CO,OL,HE,MC,BR,LS,VI,IR,NZ}")
            List<String> nonUsSuffixes) {
        this.nonUsSuffixes = nonUsSuffixes.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toUpperCase())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** True when {@code symbol}'s venue suffix is in the non-US whitelist. */
    public boolean isNonUs(String symbol) {
        if (symbol == null) return false;
        int dot = symbol.lastIndexOf('.');
        if (dot < 0 || dot == symbol.length() - 1) return false;   // no suffix -> US
        return nonUsSuffixes.contains(symbol.substring(dot + 1).toUpperCase());
    }
}
