package de.visterion.dracul.watchlist;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record PatchPositionRequest(
        @Positive Double entryPrice,
        @PositiveOrZero Double shareCount,
        @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "entryDate must be yyyy-MM-dd")
        String entryDate) {}
