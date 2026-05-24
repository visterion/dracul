package de.visterion.dracul.watchlist;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateWatchlistRequest(
        @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9.\\-]{0,9}$",
                            message = "symbol must be uppercase, 1-10 chars [A-Z0-9.-]")
        String symbol,
        @NotBlank @Pattern(regexp = "HELD|TRACKING", message = "tag must be HELD or TRACKING")
        String tag,
        String sourceVerdictId
) {}
