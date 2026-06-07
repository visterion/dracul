package de.visterion.dracul.watchlist;

import jakarta.validation.constraints.PositiveOrZero;

public record PatchPositionRequest(
        @PositiveOrZero Double entryPrice,
        @PositiveOrZero Double shareCount) {}
