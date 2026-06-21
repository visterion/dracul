package de.visterion.dracul.watchlist;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateWatchlistRequest(
        @NotBlank @Pattern(regexp = "^[A-Z0-9][A-Z0-9.\\-]{0,11}$",
                            message = "symbol must be uppercase, 1-12 chars [A-Z0-9.-]")
        String symbol,
        @NotBlank @Pattern(regexp = "HELD|TRACKING", message = "tag must be HELD or TRACKING")
        String tag,
        String sourceVerdictId
) {}
