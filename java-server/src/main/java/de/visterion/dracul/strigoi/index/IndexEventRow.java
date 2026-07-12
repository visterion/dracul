package de.visterion.dracul.strigoi.index;

import tools.jackson.databind.JsonNode;

import java.time.LocalDate;

/**
 * One persisted row of {@code index_event} (V27), mapped 1:1 from the table columns.
 * Dates come back as {@link LocalDate}; the {@code TIMESTAMPTZ} audit columns are
 * surfaced as ISO strings (same convention as the other JdbcClient repositories, e.g.
 * {@link de.visterion.dracul.strigoi.spin.SpinCandidateRow}); the two per-stage
 * snapshots are raw {@link JsonNode} (null until the enrichment slices fill them).
 */
public record IndexEventRow(
        long id,
        String symbol,
        String companyName,
        String indexName,
        String action,
        String source,
        LocalDate announcementDate,
        LocalDate effectiveDate,
        IndexEventStatus status,
        JsonNode announcedSnapshot,
        JsonNode postSnapshot,
        String promotedAt,
        String promotedPreyId,
        String discoveredAt,
        String lastCheckedAt,
        String effectiveAt,
        String closedAt,
        String abandonedAt) {}
