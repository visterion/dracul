package de.visterion.dracul.daywalker.detect;

import java.time.Instant;

/** One macro-only headline collected for the portfolio bucket (tags = wire form, "macro").
 *  {@code credibility} is the 0-1 chokepoint score (T1.4) — rides onto the
 *  MACRO_PORTFOLIO wire so the prompt's weighting guidance holds for the macro path too. */
public record MacroHeadline(String headline, String sourceSymbol, Instant datetime,
                            String tags, double credibility) {}
