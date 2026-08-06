package de.visterion.dracul.marketdata;

/** Raised when Agora is unreachable, returns an error, or the response can't be parsed. */
public class AgoraUnavailableException extends RuntimeException {

    /** Stable machine token Agora puts at the front of the error message for a document that
     *  exceeds {@code agora.data.edgar.max-filing-bytes}. Agora guarantees a genuine outage never
     *  carries it — that is the whole point of the token, and the only thing that lets a caller
     *  tell "this one document is too big" apart from "the source is down". */
    public static final String FILING_TOO_LARGE_TOKEN = "filing_too_large:";

    /**
     * What the failure is evidence ABOUT — the one thing a caller needs and the message text
     * cannot carry.
     *
     * <p>The distinction is structural, not textual: it is recorded at the throw site, where the
     * fact is actually known, instead of being reconstructed later from prose that Agora, Yahoo
     * and EDGAR each word differently. That matters because the same wire type covers two
     * completely different events — "Dracul could not talk to Agora" and "Agora answered, and its
     * answer was an error about this one request" — and only the first says anything about the
     * source's health.
     */
    public enum Scope {
        /** Agora never produced an answer: transport failure, empty body, unparseable body.
         *  Nothing about the request can explain it, so it is evidence about the SOURCE. */
        SOURCE,
        /** Agora answered, and the answer was an error envelope for THIS request — an unknown
         *  symbol, an unresolvable issuer, an oversized document. On its own it is evidence about
         *  the request only. A whole RUN of them with no success in between is a separate
         *  argument, and one the guard makes by counting (see {@code EnrichmentSourceGuard}). */
        REQUEST
    }

    private final Scope scope;

    public AgoraUnavailableException(String message, Throwable cause) { this(Scope.SOURCE, message, cause); }
    public AgoraUnavailableException(String message) { this(Scope.SOURCE, message, null); }

    public AgoraUnavailableException(Scope scope, String message, Throwable cause) {
        super(message, cause);
        this.scope = scope == null ? Scope.SOURCE : scope;
    }

    /** SOURCE by default: an unclassified failure must keep behaving exactly as it did before the
     *  scope existed — conservative, i.e. treated as an outage. */
    public Scope scope() { return scope; }

    /** The {@code AgoraUnavailableException} in {@code t} — itself, or the first one in its cause
     *  chain — or {@code null} when there is none.
     *
     *  <p>Needed because the OHLC path re-wraps: {@code AgoraMarketData} catches this exception and
     *  rethrows a {@code MarketDataException(UNAVAILABLE, e.getMessage(), e)}. The message survives
     *  that wrap, the type does not — so a caller that only looked at the outer type saw
     *  "UNAVAILABLE" for a Yahoo 404 about one symbol. The cause chain still carries the scope. */
    public static AgoraUnavailableException unwrap(Throwable t) {
        for (Throwable c = t; c != null && c != c.getCause(); c = c.getCause()) {
            if (c instanceof AgoraUnavailableException a) return a;
        }
        return null;
    }

    /** True when this failure is Agora refusing ONE oversized document rather than an outage.
     *  Shared by {@code AgoraClient}'s log branch and the filing-text facade so the two can
     *  never drift into disagreeing about what happened. */
    public boolean filingTooLarge() {
        String m = getMessage();
        return m != null && m.contains(FILING_TOO_LARGE_TOKEN);
    }
}
