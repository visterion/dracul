package de.visterion.dracul.strigoi.lazarus;

/**
 * Which listing a {@link LazarusCandidate}'s fundamentals actually describe, and how
 * confidently that was established.
 *
 * <p>The single legitimate discriminator for "these numbers describe this listing" is
 * {@code reportingCurrency != null} — never the shape of the ticker symbol. Suffix lists
 * ({@code InstrumentClassifier} and friends) tell you how a symbol is spelled, not which
 * currency its fundamentals are reported in; treating them as interchangeable is the bug
 * this enum exists to prevent from recurring.
 *
 * <ul>
 *   <li>{@link #FOREIGN_SUFFIXED} — the resolver confirmed the fundamentals describe a
 *       non-US listing (a non-null, non-USD {@code reportingCurrency}).</li>
 *   <li>{@link #US_CONFIRMED} — the resolver confirmed the fundamentals describe the US
 *       listing being screened (a null or USD {@code reportingCurrency}).</li>
 *   <li>{@link #UNKNOWN} — no resolution has happened yet. This is the only value the
 *       screener itself may ever produce, since it is pure and I/O-free and cannot call
 *       {@code get_company_profile} to find out.</li>
 * </ul>
 */
public enum ListingResolution {
    FOREIGN_SUFFIXED,
    US_CONFIRMED,
    UNKNOWN
}
