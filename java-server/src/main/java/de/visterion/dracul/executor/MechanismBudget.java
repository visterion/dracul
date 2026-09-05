package de.visterion.dracul.executor;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Per-mechanism share of the total budget that new entries may occupy, parsed once at startup from
 * {@code dracul.executor.mechanism-budget-pct} ({@code MECHANISM:fraction,...}). Mechanisms are
 * compared case-insensitively; a fraction must lie in (0, 1]. A malformed spec fails startup rather
 * than silently dropping a cap. Consumed by {@code VetoService} (check 5b, MECHANISM_BUDGET) via
 * {@link VetoConfig} and by {@code RuleVersionProvider} for the audit row. Public because it is a
 * component of the public record {@link VetoConfig}.
 */
public final class MechanismBudget {

    private static final MechanismBudget NONE = new MechanismBudget("");

    private final String spec;
    private final Map<String, Double> shares;

    public MechanismBudget(String spec) {
        this.spec = spec == null || spec.isBlank() ? "" : spec;
        this.shares = parse(this.spec);
    }

    public static MechanismBudget none() {
        return NONE;
    }

    /** The raw spec string as configured ({@code ""} for {@link #none()}). */
    public String spec() {
        return spec;
    }

    public boolean isEmpty() {
        return shares.isEmpty();
    }

    public Optional<Double> shareFor(String mechanism) {
        if (mechanism == null) return Optional.empty();
        return Optional.ofNullable(shares.get(mechanism.trim().toUpperCase(Locale.ROOT)));
    }

    private static Map<String, Double> parse(String spec) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (spec.isEmpty()) return out;
        for (String entry : spec.split(",", -1)) {
            int colon = entry.indexOf(':');
            if (colon < 0) throw bad(entry, "missing ':'");
            String key = entry.substring(0, colon).trim().toUpperCase(Locale.ROOT);
            String value = entry.substring(colon + 1).trim();
            if (key.isEmpty()) throw bad(entry, "empty mechanism");
            if (value.isEmpty()) throw bad(entry, "empty fraction");
            double share;
            try {
                share = Double.parseDouble(value);
            } catch (NumberFormatException e) {
                throw bad(entry, "fraction is not a number");
            }
            if (!(share > 0.0 && share <= 1.0)) throw bad(entry, "fraction must be in (0, 1]");
            if (out.containsKey(key)) throw bad(entry, "duplicate mechanism " + key);
            out.put(key, share);
        }
        return out;
    }

    private static IllegalArgumentException bad(String entry, String why) {
        return new IllegalArgumentException(
                "dracul.executor.mechanism-budget-pct: invalid entry '" + entry.trim() + "' (" + why + ")");
    }
}
