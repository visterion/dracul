package de.visterion.dracul.renfield;

import java.math.BigDecimal;

/** One entry of a symbol's proposal history, as returned by
 *  {@link TradeProposalRepository#findPriorBySymbols}. {@code date} is the ISO-8601
 *  instant string of {@code created_at}. */
public record PriorProposal(String date, String action, BigDecimal confidence) {
}
