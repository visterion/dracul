package de.visterion.dracul.watchlist;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateWatchlistRequest(
        // 24 chars, not 12: a global search surfaces symbols like AT0000A324Q2.VI (15), which
        // Agora quotes fine. The frontend TICKER_RE must stay identical to this pattern.
        @NotBlank @Pattern(regexp = "^[A-Z0-9][A-Z0-9.\\-]{0,23}$",
                            message = "symbol must be uppercase, 1-24 chars [A-Z0-9.-]")
        String symbol,
        @NotBlank @Pattern(regexp = "HELD|TRACKING", message = "tag must be HELD or TRACKING")
        String tag,
        String sourceVerdictId,
        /** Company name from the instrument search; get_quote carries none. Blank == absent. */
        @Size(max = 128, message = "name must be at most 128 chars")
        String name
) {
    /** The supplied name, or null when absent/blank. */
    public String nameOrNull() {
        return name == null || name.isBlank() ? null : name.trim();
    }
}
