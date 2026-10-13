package de.visterion.dracul.benchmark;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** One screen_candidate row; offer fields come from extra (merger only, else null). */
public record CandidateRow(String agent, String symbol, Instant fetchedAt, LocalDate asOfDate,
                           BigDecimal offerPrice, String offerCurrency) {
}
