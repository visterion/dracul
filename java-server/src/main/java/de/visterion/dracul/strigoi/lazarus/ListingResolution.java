package de.visterion.dracul.strigoi.lazarus;

/**
 * Which listing a {@link LazarusCandidate}'s fundamentals actually describe, and how
 * confidently that was established.
 *
 * <p>The single legitimate discriminator for "these numbers describe a foreign listing" is
 * {@code reportingCurrency != null} — never the shape of the ticker symbol, and never the
 * currency value itself. A non-null {@code reportingCurrency} means foreign REGARDLESS of
 * which currency it names: 0005.HK reports in USD and is still a foreign listing. Suffix
 * lists (like the former {@code InstrumentClassifier}) tell you how a symbol is spelled, not
 * which listing its fundamentals describe; treating them as interchangeable is the bug this
 * enum exists to prevent from recurring.
 *
 * <ul>
 *   <li>{@link #FOREIGN_SUFFIXED} — {@code reportingCurrency} is non-null, i.e. present at
 *       all. Currency-agnostic: {@code "USD"} qualifies exactly like {@code "EUR"} or
 *       {@code "CNY"}.</li>
 *   <li>{@link #US_CONFIRMED} — the resolver called {@code get_company_profile} and the
 *       returned {@code ticker} matches the symbol being screened. This is the ONLY basis for
 *       this value. Neither the symbol's shape nor {@code reportingCurrency} being null or
 *       {@code "USD"} is a legitimate substitute for that profile call — assigning
 *       {@code US_CONFIRMED} from currency alone, without ever resolving the listing, is
 *       exactly the bug this plan removes.</li>
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
