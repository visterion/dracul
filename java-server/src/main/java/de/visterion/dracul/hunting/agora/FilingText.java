package de.visterion.dracul.hunting.agora;

/** A filing's extracted summary/term-sheet text. Fail-soft: {@code available == false}
 *  with empty text when Agora could not deliver it.
 *
 *  <p>{@code failure} says WHY it is missing, because the two reasons need different handling
 *  and different words in a log: a document Agora refused for exceeding its size cap
 *  ({@link Failure#TOO_LARGE}) is a property of that one filing and will fail again on every
 *  retry, whereas {@link Failure#UNAVAILABLE} is a transient source problem. Six DEFM14A proxies
 *  failed on every production run and both causes looked identical from here. */
public record FilingText(String text, boolean available, Failure failure) {

    public enum Failure {
        /** Text was delivered. */
        NONE,
        /** Agora could not deliver it (outage, error envelope, unparseable response). */
        UNAVAILABLE,
        /** Agora refused it: the document exceeds {@code agora.data.edgar.max-filing-bytes}. */
        TOO_LARGE
    }

    /** Two-arg form kept for the many call sites that only distinguish available/not. */
    public FilingText(String text, boolean available) {
        this(text, available, available ? Failure.NONE : Failure.UNAVAILABLE);
    }

    public static FilingText unavailable() { return new FilingText("", false, Failure.UNAVAILABLE); }

    public static FilingText tooLarge() { return new FilingText("", false, Failure.TOO_LARGE); }
}
