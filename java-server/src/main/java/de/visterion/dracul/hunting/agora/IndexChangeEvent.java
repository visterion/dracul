package de.visterion.dracul.hunting.agora;

import java.time.LocalDate;

/**
 * One announced index-constituent change fetched via Agora
 * ({@code get_index_constituent_changes}). Neutral reference-data DTO: a ticker joining
 * or leaving an index on a given effective date, with the announcement date and the
 * source that surfaced it (S&amp;P press release vs Russell reconstitution).
 *
 * <p>{@code companyName} is not carried by the Agora tool (it emits ticker-level changes
 * only) and is therefore passed through empty/null; Dracul enriches the name later.
 * {@code action} is {@code add} or {@code remove}; {@code index} is one of
 * {@code sp500}/{@code russell1000}/{@code russell2000}.
 */
public record IndexChangeEvent(
        String symbol,
        String companyName,
        String index,
        String action,
        LocalDate announcementDate,
        LocalDate effectiveDate,
        String source
) {}
