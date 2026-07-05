package de.visterion.dracul.settings;

import java.util.List;

/** Live health of one market-data source for the Settings → data-sources section. */
public record DataSourceHealth(
        String id,             // "agora" — Dracul's single upstream data service (was per-provider pre-7d)
        String label,
        boolean configured,
        String status,         // ok | rate_limited | error | not_configured | timeout
        Integer httpStatus,    // nullable
        String detail,         // nullable
        Long latencyMs,        // nullable
        List<String> usedBy,
        String rateLimitNote,
        String checkedAt       // ISO instant
) {}
