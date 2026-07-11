package de.visterion.dracul.outcome;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;

/**
 * One row of the {@code outcome_log} table: either the realized outcome of an executed trade
 * ({@code kind=TRADE}) or the "what would have happened" counterfactual of a rejected entry
 * signal ({@code kind=COUNTERFACTUAL}). Written exclusively by {@link OutcomeBatchJob}; the
 * Executor itself never reads or writes this table.
 */
public record OutcomeLogRow(
        String kind,
        String logIdRef,
        Long positionId,
        String symbol,
        String reasonCode,
        Boolean filled,
        BigDecimal fillPrice,
        BigDecimal slippageVsLimit,
        Integer holdingDays,
        BigDecimal mfeR,
        BigDecimal maeR,
        BigDecimal realizedR,
        String exitTrigger,
        String exitLogId,
        JsonNode partialExits,
        Boolean reentryWithin10d,
        Boolean roundtripUnder5d,
        JsonNode hypothetical,
        Boolean hunterLabel,
        String sourceAgent,
        String agentVersion,
        String ruleVersion,
        boolean complete) {
}
