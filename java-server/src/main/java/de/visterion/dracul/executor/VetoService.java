package de.visterion.dracul.executor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Code-enforced pre-trade vetos ("Garantien in Code"). Pure and deterministic — no I/O, no clock.
 * The LLM's judgment never overrides these.
 *
 * <p>{@link #evaluate} runs the full 13-veto catalog against an assembled {@link EntryContext},
 * preceded by a {@code DATA_UNAVAILABLE} pre-veto that short-circuits everything else whenever
 * mandatory upstream data was missing at assembly time.
 */
@Service
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class VetoService {

    /** Mechanisms that contradict MERGER_ARB on the same symbol (checked both directions). */
    private static final Set<String> MERGER_ARB_CONTRADICTIONS = Set.of(
            "PEAD", "SPINOFF", "INSIDER_CLUSTER", "INDEX_INCLUSION", "QUALITY_52W_LOW");

    /** Result of evaluating the full veto catalog against one signal. */
    public record Outcome(boolean passed, RejectReason firstFailure, List<VetoResult> results,
                           String contradictingSignalId) {}

    public Outcome evaluate(ExecutorSignal signal, EntryContext ctx, Sizing sizing, VetoConfig cfg) {
        if (ctx.missing() != null && !ctx.missing().isEmpty()) {
            String joined = String.join(",", ctx.missing());
            return new Outcome(false, RejectReason.DATA_UNAVAILABLE,
                    List.of(new VetoResult("DATA_UNAVAILABLE:" + joined, false)), null);
        }

        List<VetoResult> results = new ArrayList<>();
        RejectReason firstFailure = null;
        String contradictingSignalId = null;

        // 1 SCHEMA_INVALID
        boolean schemaOk = signal != null
                && signal.symbol() != null && !signal.symbol().isBlank()
                && signal.direction() != null && !signal.direction().isBlank()
                && signal.confidence() != null
                && signal.killCriteria() != null && !signal.killCriteria().isEmpty()
                && signal.mechanism() != null && !signal.mechanism().isBlank()
                && signal.agentVersion() != null && !signal.agentVersion().isBlank();
        results.add(new VetoResult("SCHEMA_INVALID", schemaOk));
        if (!schemaOk && firstFailure == null) firstFailure = RejectReason.SCHEMA_INVALID;

        // 2 LOW_CONFIDENCE (only meaningful once schema passed, confidence non-null)
        boolean confidenceOk = schemaOk && signal.confidence() >= cfg.minConfidence();
        results.add(new VetoResult("LOW_CONFIDENCE", confidenceOk));
        if (!confidenceOk && firstFailure == null) firstFailure = RejectReason.LOW_CONFIDENCE;

        // 3 COOLDOWN — deterministic v1 rule: ANY active cooldown row matching the symbol fails
        // the entry, with no exception. Rationale: the cooldown's ORIGIN mechanism is not stored
        // anywhere (the position that caused it is closed and thus absent from openMechanisms), so
        // no formulation over open positions — whole-book or symbol-scoped — can ever verify "this
        // is a genuinely new mechanism, not the one that triggered the cooldown". Per the executor
        // spec's "when in doubt, fail" principle, an active cooldown is therefore a hard block in
        // v1. A real fresh-setup exception returns as a fast-follow once a `cooldown.mechanism`
        // column exists to record the origin.
        // Note: unevaluated because the signal is schema-invalid still traces as PASS here — this
        // veto and the ones below gated on schemaOk only ever *fail* when schemaOk is true, so a
        // schema-invalid signal's trace shows PASS for all of them while SCHEMA_INVALID itself is
        // firstFailure (trace consistency, matches SCHEMA_INVALID/LOW_CONFIDENCE ordering).
        boolean cooldownOk = true;
        if (schemaOk) {
            for (Cooldown cd : ctx.activeCooldowns()) {
                if (signal.symbol().equals(cd.symbol())) {
                    cooldownOk = false;
                    break;
                }
            }
        }
        results.add(new VetoResult("COOLDOWN", cooldownOk));
        if (!cooldownOk && firstFailure == null) firstFailure = RejectReason.COOLDOWN;

        // 4 MAX_POSITIONS
        boolean capacityOk = ctx.openPositions().size() < cfg.maxPositions();
        results.add(new VetoResult("MAX_POSITIONS", capacityOk));
        if (!capacityOk && firstFailure == null) firstFailure = RejectReason.MAX_POSITIONS;

        // 5 BUDGET — all account ccy
        BigDecimal trancheAccountCcy = cfg.totalBudget().divide(BigDecimal.TEN);
        boolean budgetOk = ctx.account() != null
                && ctx.account().cash().compareTo(trancheAccountCcy) >= 0
                && ctx.openExposure().add(trancheAccountCcy).compareTo(cfg.totalBudget()) <= 0;
        results.add(new VetoResult("BUDGET", budgetOk));
        if (!budgetOk && firstFailure == null) firstFailure = RejectReason.BUDGET;

        // 6 HEAT_LIMIT
        BigDecimal heatLimit = cfg.totalBudget().multiply(BigDecimal.valueOf(cfg.heatPct()));
        boolean heatOk = ctx.openHeat().add(sizing.newRiskAccountCcy())
                .compareTo(heatLimit) <= 0;
        results.add(new VetoResult("HEAT_LIMIT", heatOk));
        if (!heatOk && firstFailure == null) firstFailure = RejectReason.HEAT_LIMIT;

        // 7 CONCENTRATION — case-insensitive sector match
        long sameSectorCount = ctx.openPositions().stream()
                .filter(p -> p.sector() != null
                        && p.sector().equalsIgnoreCase(ctx.candidateSector()))
                .count();
        boolean concentrationOk = sameSectorCount < cfg.maxPerSector();
        results.add(new VetoResult("CONCENTRATION", concentrationOk));
        if (!concentrationOk && firstFailure == null) firstFailure = RejectReason.CONCENTRATION;

        // 8 CONTRADICTION — MERGER_ARB vs {PEAD, SPINOFF, INSIDER_CLUSTER, INDEX_INCLUSION,
        // QUALITY_52W_LOW}, both directions, same symbol. Checked against other pending signals
        // (records the contradicting signal id) and against open-position mechanisms.
        boolean contradictionOk = true;
        if (schemaOk) {
            for (ExecutorSignal pending : ctx.pendingSignals()) {
                if (pending.signalId() != null && pending.signalId().equals(signal.signalId())) continue;
                if (!signal.symbol().equals(pending.symbol())) continue;
                if (isContradictingPair(signal.mechanism(), pending.mechanism())) {
                    contradictionOk = false;
                    contradictingSignalId = pending.signalId();
                    break;
                }
            }
            if (contradictionOk) {
                String openMechanism = ctx.openMechanisms().get(signal.symbol());
                if (openMechanism != null && isContradictingPair(signal.mechanism(), openMechanism)) {
                    contradictionOk = false;
                }
            }
        }
        results.add(new VetoResult("CONTRADICTION", contradictionOk));
        if (!contradictionOk && firstFailure == null) firstFailure = RejectReason.CONTRADICTION;

        // 9 REDUNDANCY — same mechanism already open on the same symbol
        boolean redundancyOk = !(schemaOk
                && signal.mechanism().equals(ctx.openMechanisms().get(signal.symbol())));
        results.add(new VetoResult("REDUNDANCY", redundancyOk));
        if (!redundancyOk && firstFailure == null) firstFailure = RejectReason.REDUNDANCY;

        // 10 LIQUIDITY
        boolean liquidityOk = ctx.price().compareTo(cfg.minPrice()) >= 0
                && ctx.adv20Notional().compareTo(
                        ctx.trancheAmount().multiply(BigDecimal.valueOf(cfg.advMultiple()))) >= 0;
        results.add(new VetoResult("LIQUIDITY", liquidityOk));
        if (!liquidityOk && firstFailure == null) firstFailure = RejectReason.LIQUIDITY;

        // 11 SIGNAL_EXPIRED
        boolean expiredOk = ctx.signalAgeTradingDays() <= cfg.maxSignalAgeDays();
        results.add(new VetoResult("SIGNAL_EXPIRED", expiredOk));
        if (!expiredOk && firstFailure == null) firstFailure = RejectReason.SIGNAL_EXPIRED;

        // 12 CHASED_AWAY — unlike the other signal-dependent vetos above, this one is NOT gated by
        // schemaOk: a null signal, or a schema-valid signal with a null referencePrice (a field not
        // covered by SCHEMA_INVALID's checklist), can otherwise reach this line and NPE. Made total:
        // schema-invalid ⇒ traces PASS (consistent with the other gated vetos; SCHEMA_INVALID is
        // already firstFailure); schema-valid but referencePrice == null ⇒ FAILS conservatively (an
        // unverifiable chase check is not tradeable). In production the assembler already routes a
        // null reference price into the DATA_UNAVAILABLE pre-veto, so this branch is defense-in-depth
        // for direct/pure calls to evaluate().
        boolean chasedOk;
        if (!schemaOk) {
            chasedOk = true;
        } else if (signal.referencePrice() == null) {
            chasedOk = false;
        } else {
            BigDecimal chaseThreshold = signal.referencePrice()
                    .add(ctx.atr().multiply(BigDecimal.valueOf(cfg.chaseAtrMult())));
            chasedOk = ctx.price().compareTo(chaseThreshold) <= 0;
        }
        results.add(new VetoResult("CHASED_AWAY", chasedOk));
        if (!chasedOk && firstFailure == null) firstFailure = RejectReason.CHASED_AWAY;

        // 13 PACE_LIMIT
        boolean paceOk = ctx.entriesThisWeek() < cfg.pacePerWeek();
        results.add(new VetoResult("PACE_LIMIT", paceOk));
        if (!paceOk && firstFailure == null) firstFailure = RejectReason.PACE_LIMIT;

        boolean passed = firstFailure == null;
        return new Outcome(passed, firstFailure, results, contradictingSignalId);
    }

    private boolean isContradictingPair(String mechanismA, String mechanismB) {
        if (mechanismA == null || mechanismB == null) return false;
        return ("MERGER_ARB".equals(mechanismA) && MERGER_ARB_CONTRADICTIONS.contains(mechanismB))
                || ("MERGER_ARB".equals(mechanismB) && MERGER_ARB_CONTRADICTIONS.contains(mechanismA));
    }
}
