package de.visterion.dracul.gropar;

import java.util.List;

public record ExitSignal(
        String id,
        String watchlistItemId,   // nullable
        String symbol,
        String action,            // SELL | TRIM | HOLD
        List<String> firedRules,
        Double gainLossPct,
        String thesisStatus,      // INTACT | WEAKENING | INVALIDATED | NONE
        String rationale,
        Double confidence,
        String vistierieRunId,
        String runAt
) {}
