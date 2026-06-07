package de.visterion.dracul.watchlist;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record PatchPositionRequest(
        @Positive Double entryPrice,
        @PositiveOrZero Double shareCount) {}
