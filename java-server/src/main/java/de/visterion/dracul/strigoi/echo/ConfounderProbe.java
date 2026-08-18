package de.visterion.dracul.strigoi.echo;

import java.util.List;

/**
 * Outcome of one fetch-and-scan confounder read: the flags found if the source answered, and —
 * when it did not — that the read is UNKNOWN rather than an implicit "no confounders found".
 *
 * <p>Modelled on {@code AgoraPriceRange}'s {@code RangeProbe}: a plain {@code List<String>} (or a
 * list plus an unrelated boolean) cannot carry this distinction safely, because "empty" would then
 * mean both "scanned, nothing matched" and "did not scan at all" — exactly the conflation this type
 * exists to remove. {@link #flags()} is only meaningful when {@link #unknown()} is false.
 */
public record ConfounderProbe(List<String> flags, ConfounderProbe.Kind kind) {

    public enum Kind {
        /** The news source answered; {@link #flags()} is the (possibly empty) scan result. */
        SCANNED,
        /** The news source did not answer; {@link #flags()} is empty and must not be read as clean. */
        UNKNOWN
    }

    public static ConfounderProbe of(List<String> flags) {
        return new ConfounderProbe(flags, Kind.SCANNED);
    }

    public static ConfounderProbe sourceDown() {
        return new ConfounderProbe(List.of(), Kind.UNKNOWN);
    }

    public boolean unknown() {
        return kind == Kind.UNKNOWN;
    }
}
