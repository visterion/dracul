package de.visterion.dracul.hunting.agora;

/** A filing's extracted summary/term-sheet text. Fail-soft: {@code available == false}
 *  with empty text when Agora could not deliver it. */
public record FilingText(String text, boolean available) {
    public static FilingText unavailable() { return new FilingText("", false); }
}
