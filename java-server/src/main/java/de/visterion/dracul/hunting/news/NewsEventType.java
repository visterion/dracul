package de.visterion.dracul.hunting.news;

import java.util.Optional;

/**
 * Shared typed news-event taxonomy (T1.3). Keywords are lowercase substrings matched over
 * {@code headline + " " + summary} (see {@link NewsEventTagger}); the five Echo-blocking
 * types carry the exact historical ConfounderScreen flag strings as {@code label()} so
 * Echo skip-reasons and the persisted Index confounder list stay byte-identical.
 * {@code wireValue()} is the lowercase-snake form used in event payloads, the
 * daywalker-assessment schema, and the {@code daywalker_alerts.event_type} column.
 */
public enum NewsEventType {

    MA("ma", "m&a", true,
            "merger", "acquisition", "to acquire", "acquire ", "acquires", "buyout", "takeover"),
    RESTATEMENT("restatement", "restatement", true,
            "restate", "restatement"),
    GUIDANCE_CUT("guidance_cut", "guidance-cut", true,
            "cuts guidance", "lowers guidance", "cuts forecast", "lowers outlook",
            "guidance cut", "slashes forecast"),
    DILUTION("dilution", "dilution", true,
            "public offering", "share offering", "stock offering", "dilution", "secondary offering"),
    INVESTIGATION("investigation", "investigation", true,
            "sec investigation", "sec probe", "fraud"),
    EARNINGS_MISS("earnings_miss", "earnings-miss", false,
            "misses estimates", "earnings miss", "missed expectations",
            "falls short of estimates", "profit warning"),
    MACRO("macro", "macro", false,
            "fed raises", "fed cuts", "rate hike", "rate cut", "tariff", "tariffs",
            "inflation data", "recession fears");

    private final String wireValue;
    private final String label;
    private final boolean blocksEcho;
    private final String[] keywords;

    NewsEventType(String wireValue, String label, boolean blocksEcho, String... keywords) {
        this.wireValue = wireValue;
        this.label = label;
        this.blocksEcho = blocksEcho;
        this.keywords = keywords;
    }

    /** Lowercase-snake payload/schema/DB value. */
    public String wireValue() { return wireValue; }

    /** Historical ConfounderScreen flag string — MUST stay char-for-char stable. */
    public String label() { return label; }

    /** Whether this type hard-blocks the Echo hunter (EARNINGS_MISS/MACRO do not). */
    public boolean blocksEcho() { return blocksEcho; }

    String[] keywords() { return keywords; }

    /** Resolve a wire value back to its type; empty for null/unknown (incl. "other"/"none"). */
    public static Optional<NewsEventType> fromWire(String wire) {
        for (NewsEventType t : values()) {
            if (t.wireValue.equals(wire)) return Optional.of(t);
        }
        return Optional.empty();
    }
}
