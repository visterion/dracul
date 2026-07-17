package de.visterion.dracul.daywalker.detect;

import java.time.Instant;

/** One macro-only headline collected for the portfolio bucket (tags = wire form, "macro"). */
public record MacroHeadline(String headline, String sourceSymbol, Instant datetime, String tags) {}
