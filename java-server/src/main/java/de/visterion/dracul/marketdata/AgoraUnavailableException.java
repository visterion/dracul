package de.visterion.dracul.marketdata;

/** Raised when Agora is unreachable, returns an error, or the response can't be parsed. */
public class AgoraUnavailableException extends RuntimeException {

    /** Stable machine token Agora puts at the front of the error message for a document that
     *  exceeds {@code agora.data.edgar.max-filing-bytes}. Agora guarantees a genuine outage never
     *  carries it — that is the whole point of the token, and the only thing that lets a caller
     *  tell "this one document is too big" apart from "the source is down". */
    public static final String FILING_TOO_LARGE_TOKEN = "filing_too_large:";

    public AgoraUnavailableException(String message, Throwable cause) { super(message, cause); }
    public AgoraUnavailableException(String message) { super(message, null); }

    /** True when this failure is Agora refusing ONE oversized document rather than an outage.
     *  Shared by {@code AgoraClient}'s log branch and the filing-text facade so the two can
     *  never drift into disagreeing about what happened. */
    public boolean filingTooLarge() {
        String m = getMessage();
        return m != null && m.contains(FILING_TOO_LARGE_TOKEN);
    }
}
