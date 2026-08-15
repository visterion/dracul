package de.visterion.dracul.strigoi.spin;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One persisted row of {@code spin_candidate} (V26), mapped 1:1 from the table
 * columns. Dates come back as {@link LocalDate}; the {@code TIMESTAMPTZ} audit
 * columns are surfaced as ISO strings (same convention as the other JdbcClient
 * repositories, e.g. {@code ExitSignalRepository}); the three per-stage snapshots
 * are raw {@link JsonNode} (null until the enrichment slices fill them).
 *
 * <p>{@code termSheetText} carries the raw information-statement prose (kept against the
 * blueprint's lean-table preference so the LLM can read the parent/size/forced-selling rationale
 * that lives only in prose, and so {@code termSheetAvailable} stays truthful). {@code parentSymbol}
 * is the best-effort parent ticker extracted from that prose (null when only a name appears).
 *
 * <p>{@code termsCheckedAt} (V44) is the one {@code TIMESTAMPTZ} column surfaced as {@link Instant}
 * rather than an ISO string, by design: {@link SpinCandidateEnricher} does arithmetic on it (the
 * 7-day term-capture throttle) every enrichment pass, and since D2 removed date extraction from
 * {@link SpinTermsParser}, {@code recordDate}/{@code distributionDate} can never again turn the old
 * capture precondition false — this column is the only thing standing between a DISTRIBUTED row
 * and a term-sheet re-fetch on every single run.
 */
public record SpinCandidateRow(
        long id,
        String cik,
        String symbol,
        String companyName,
        String formType,
        LocalDate filingDate,
        String filingUrl,
        String distributionRatio,
        LocalDate recordDate,
        LocalDate distributionDate,
        boolean termSheetAvailable,
        String termSheetText,
        String parentSymbol,
        SpinStatus status,
        JsonNode registeredSnapshot,
        JsonNode distributedSnapshot,
        JsonNode settledSnapshot,
        String promotedAt,
        String promotedPreyId,
        String discoveredAt,
        String lastCheckedAt,
        String distributedAt,
        String settledAt,
        String abandonedAt,
        Instant termsCheckedAt) {}
