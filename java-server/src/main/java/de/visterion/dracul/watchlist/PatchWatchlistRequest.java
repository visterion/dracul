package de.visterion.dracul.watchlist;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PatchWatchlistRequest(
        @NotBlank @Pattern(regexp = "HELD|TRACKING", message = "tag must be HELD or TRACKING")
        String tag
) {}
