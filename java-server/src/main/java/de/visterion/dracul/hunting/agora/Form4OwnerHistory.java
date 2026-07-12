package de.visterion.dracul.hunting.agora;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Multi-year Form-4 transaction history for ONE company, grouped per SEC reporting owner
 * (Agora's {@code get_form4_owner_history}). Neutral Dracul DTO — the mapping in
 * {@link AgoraFilings#ownerHistoryStrict(String)} does no interpretation; the
 * routine/opportunistic classification lives in the strigoi-insider helpers.
 *
 * <p>{@code truncated == true} means the returned history is INCOMPLETE (the fetch hit its
 * deadline or the tool's limit cap). A consumer that concludes "no recurring calendar pattern"
 * from a truncated history is reasoning on an incomplete basis and MUST treat that conclusion
 * as unknown rather than as evidence of an opportunistic (musterabweichend) buy.
 */
public record Form4OwnerHistory(
        String cik,
        LocalDate from,
        LocalDate to,
        List<Owner> owners,
        boolean truncated
) {

    /** One SEC reporting owner (identity is {@code cik}; {@code name} is display-only and can
     *  vary in casing/suffixes and — on joint filings — carry co-owners). */
    public record Owner(String name, String cik, String role, List<Transaction> transactions) {}

    /** One non-derivative transaction. {@code code} is the SEC transaction code ("P" = open-market
     *  purchase, "S" = sale, ...); {@code acquiredDisposedCode} the "A"/"D" flag. Nullable numeric
     *  fields are absent/unparsable in the source filing. {@code aff10b5One} is the tri-state Rule
     *  10b5-1(c) plan flag: {@code TRUE}/{@code FALSE} when the filing carries the 2023+ checkbox,
     *  {@code null} when it predates it — {@code null} means "unknown", NOT "not a plan trade". */
    public record Transaction(
            LocalDate transactionDate,
            String code,
            String acquiredDisposedCode,
            String form,
            BigDecimal shares,
            BigDecimal price,
            BigDecimal dollarValue,
            BigDecimal sharesOwnedFollowing,
            Boolean aff10b5One
    ) {}
}
