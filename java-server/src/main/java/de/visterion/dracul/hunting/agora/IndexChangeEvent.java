package de.visterion.dracul.hunting.agora;

import java.time.LocalDate;

/**
 * One announced index-constituent change fetched via Agora
 * ({@code get_index_constituent_changes}). Neutral reference-data DTO: a ticker joining
 * or leaving an index on a given effective date, with the announcement date and the
 * source that surfaced it (S&amp;P press release vs Russell reconstitution).
 *
 * <p>{@code companyName} is best effort: Agora reads it off the S&amp;P press-release prose /
 * the FTSE Russell reconstitution list, and emits null when it cannot. It arrives here as the
 * empty string in that case, and Dracul then tries the index membership list
 * ({@link AgoraIndexConstituents}) — which can only name a symbol that is a member right now,
 * i.e. the leaving side of a pending change, not the entering one. It therefore stays legitimately
 * empty for some rows, and is never guessed from the ticker.
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
