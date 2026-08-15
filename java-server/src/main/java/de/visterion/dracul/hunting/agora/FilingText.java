package de.visterion.dracul.hunting.agora;

/** A filing's extracted summary/term-sheet text. Fail-soft: {@code available == false}
 *  with empty text when Agora could not deliver it.
 *
 *  <p>{@code failure} says WHY it is missing, because the two reasons need different handling
 *  and different words in a log: a document Agora refused for exceeding its size cap
 *  ({@link Failure#TOO_LARGE}) is a property of that one filing and will fail again on every
 *  retry, whereas {@link Failure#UNAVAILABLE} is a transient source problem. Six DEFM14A proxies
 *  failed on every production run and both causes looked identical from here.
 *
 *  <p>{@code resolvedExhibit} (D1/D3) carries Agora's {@code resolved_exhibit} from a
 *  {@code get_filing_text} call that asked for a named exhibit (e.g. {@code "EX-99.1"}): the
 *  exhibit type actually found, or {@code null} when the exhibit was not present and Agora fell
 *  back to the primary document (the Form-10 shell). A caller that already holds good term-sheet
 *  text must treat that fallback as "not what was asked for", not as a fresh, trustworthy answer
 *  — a successful fetch can still carry the wrong document. Always {@code null} for a call that
 *  did not request an exhibit at all. */
public record FilingText(String text, boolean available, Failure failure, String resolvedExhibit) {

    public enum Failure {
        /** Text was delivered. */
        NONE,
        /** Agora could not deliver it (outage, error envelope, unparseable response). */
        UNAVAILABLE,
        /** Agora refused it: the document exceeds {@code agora.data.edgar.max-filing-bytes}. */
        TOO_LARGE
    }

    /** Two-arg form kept for the many call sites that only distinguish available/not. No exhibit
     *  was requested, so {@code resolvedExhibit} is null. */
    public FilingText(String text, boolean available) {
        this(text, available, available ? Failure.NONE : Failure.UNAVAILABLE, null);
    }

    /** Three-arg form kept for {@link #unavailable()}/{@link #tooLarge()} and any caller that
     *  states the failure kind explicitly without an exhibit. */
    public FilingText(String text, boolean available, Failure failure) {
        this(text, available, failure, null);
    }

    public static FilingText unavailable() { return new FilingText("", false, Failure.UNAVAILABLE); }

    public static FilingText tooLarge() { return new FilingText("", false, Failure.TOO_LARGE); }
}
