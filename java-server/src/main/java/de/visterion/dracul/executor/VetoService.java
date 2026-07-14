package de.visterion.dracul.executor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Code-enforced pre-trade vetos ("Garantien in Code"). Pure and deterministic — no I/O, no clock.
 * The LLM's judgment never overrides these.
 *
 * <p>{@link #evaluate} runs the full 16-veto catalog against an assembled {@link EntryContext},
 * preceded by a {@code DATA_UNAVAILABLE} pre-veto that short-circuits everything else whenever
 * mandatory upstream data was missing at assembly time.
 */
@Service
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class VetoService {

    /** Mechanisms that contradict MERGER_ARB on the same symbol (checked both directions). */
    private static final Set<String> MERGER_ARB_CONTRADICTIONS = Set.of(
            "PEAD", "SPINOFF", "INSIDER_CLUSTER", "INDEX_INCLUSION", "QUALITY_52W_LOW");

    /** Drift-continuation mechanisms: below the anchor the thesis is dead → tight BELOW_ANCHOR
     *  (drift-anchor-atr-mult, default 0). Every other mechanism is value/dip (wide). */
    private static final Set<String> DRIFT_ANCHOR_MECHANISMS = Set.of("PEAD", "INDEX_INCLUSION");

    /** Result of evaluating the full veto catalog against one signal. */
    public record Outcome(boolean passed, RejectReason firstFailure, List<VetoResult> results,
                           String contradictingSignalId, Snapshot snapshot) {}

    /**
     * Values already computed inside {@link #evaluate} for its own checks, surfaced so the
     * decision-log audit trail (rich {@code decision_log} rows) never has to recompute them —
     * one source of truth per number. Null on the {@code DATA_UNAVAILABLE} short-circuit path,
     * where none of these were ever computed.
     */
    public record Snapshot(double heatBeforePct, double heatAfterPct, BigDecimal budgetFree,
                            int newPositionsThisWeek, int sectorCountSame, String cooldownStatus) {}

    /** Back-compat overload: defense-in-depth / direct callers with no explicit order price
     *  evaluate against the current market price. */
    public Outcome evaluate(ExecutorSignal signal, EntryContext ctx, Sizing sizing, VetoConfig cfg) {
        return evaluate(signal, ctx, sizing, cfg, ctx == null ? null : ctx.price());
    }

    public Outcome evaluate(ExecutorSignal signal, EntryContext ctx, Sizing sizing, VetoConfig cfg,
                            BigDecimal orderPrice) {
        if (ctx.missing() != null && !ctx.missing().isEmpty()) {
            String joined = String.join(",", ctx.missing());
            return new Outcome(false, RejectReason.DATA_UNAVAILABLE,
                    List.of(new VetoResult("DATA_UNAVAILABLE:" + joined, false, joined)), null, null);
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
        String schemaMeasured;
        if (signal == null) {
            schemaMeasured = "missing: signal";
        } else if (signal.symbol() == null || signal.symbol().isBlank()) {
            schemaMeasured = "missing: symbol";
        } else if (signal.direction() == null || signal.direction().isBlank()) {
            schemaMeasured = "missing: direction";
        } else if (signal.confidence() == null) {
            schemaMeasured = "missing: confidence";
        } else if (signal.killCriteria() == null || signal.killCriteria().isEmpty()) {
            schemaMeasured = "missing: kill_criteria";
        } else if (signal.mechanism() == null || signal.mechanism().isBlank()) {
            schemaMeasured = "missing: mechanism";
        } else if (signal.agentVersion() == null || signal.agentVersion().isBlank()) {
            schemaMeasured = "missing: agent_version";
        } else {
            schemaMeasured = "kill_criteria: " + signal.killCriteria().size()
                    + ", mechanism: " + signal.mechanism()
                    + ", agent_version: " + signal.agentVersion();
        }
        results.add(new VetoResult("SCHEMA_INVALID", schemaOk, schemaMeasured));
        if (!schemaOk && firstFailure == null) firstFailure = RejectReason.SCHEMA_INVALID;

        // 2 LOW_CONFIDENCE (only meaningful once schema passed, confidence non-null)
        boolean confidenceOk = schemaOk && signal.confidence() >= cfg.minConfidence();
        String confidenceMeasured = (signal != null && signal.confidence() != null)
                ? signal.confidence() + (confidenceOk ? " >= " : " < ") + cfg.minConfidence()
                : "confidence unavailable";
        results.add(new VetoResult("LOW_CONFIDENCE", confidenceOk, confidenceMeasured));
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
        String cooldownMeasured = "not on list";
        if (schemaOk) {
            for (Cooldown cd : ctx.activeCooldowns()) {
                if (signal.symbol().equals(cd.symbol())) {
                    cooldownOk = false;
                    cooldownMeasured = (cd.exceptionCondition() != null && !cd.exceptionCondition().isBlank())
                            ? "active_with_fresh_setup"
                            : "active until " + cd.expiresAt();
                    break;
                }
            }
        }
        results.add(new VetoResult("COOLDOWN", cooldownOk, cooldownMeasured));
        if (!cooldownOk && firstFailure == null) firstFailure = RejectReason.COOLDOWN;

        // 4 MAX_POSITIONS
        boolean capacityOk = ctx.openPositions().size() < cfg.maxPositions();
        String maxPositionsMeasured = ctx.openPositions().size() + (capacityOk ? " < " : " >= ") + cfg.maxPositions();
        results.add(new VetoResult("MAX_POSITIONS", capacityOk, maxPositionsMeasured));
        if (!capacityOk && firstFailure == null) firstFailure = RejectReason.MAX_POSITIONS;

        // 5 BUDGET + 6 HEAT_LIMIT — all account ccy; shared arithmetic with
        // ExecutorWebhookController.addTranche via CapitalBounds so the two capital-bounds
        // enforcement points can never silently drift apart.
        CapitalBounds.Result bounds = CapitalBounds.check(ctx.account(), ctx.openExposure(),
                ctx.openHeat(), sizing.newRiskAccountCcy(), cfg.totalBudget(), cfg.trancheCount(),
                cfg.heatPct());
        boolean budgetOk = bounds.budgetOk();
        BigDecimal cash = ctx.account() != null ? ctx.account().cash() : BigDecimal.ZERO;
        BigDecimal exposureAfter = ctx.openExposure().add(bounds.trancheAccountCcy());
        BigDecimal budgetFree = cfg.totalBudget().subtract(exposureAfter);
        String budgetMeasured = "cash " + fmt2(cash) + (cash.compareTo(bounds.trancheAccountCcy()) >= 0 ? " >= " : " < ")
                + "tranche " + fmt2(bounds.trancheAccountCcy())
                + "; exposure " + fmt2(exposureAfter) + (exposureAfter.compareTo(cfg.totalBudget()) <= 0 ? " <= " : " > ")
                + "budget " + fmt2(cfg.totalBudget());
        results.add(new VetoResult("BUDGET", budgetOk, budgetMeasured));
        if (!budgetOk && firstFailure == null) firstFailure = RejectReason.BUDGET;

        // 6 HEAT_LIMIT
        boolean heatOk = bounds.heatOk();
        BigDecimal heatUsed = ctx.openHeat().add(sizing.newRiskAccountCcy());
        double heatBeforePct = cfg.totalBudget().signum() == 0 ? 0.0
                : ctx.openHeat().divide(cfg.totalBudget(), 6, RoundingMode.HALF_UP).doubleValue() * 100;
        double usedPct = cfg.totalBudget().signum() == 0 ? 0.0
                : heatUsed.divide(cfg.totalBudget(), 6, RoundingMode.HALF_UP).doubleValue() * 100;
        double limitPct = cfg.heatPct() * 100;
        String heatMeasured = String.format("%.1f%% %s %.1f%%", usedPct, heatOk ? "<=" : ">", limitPct);
        results.add(new VetoResult("HEAT_LIMIT", heatOk, heatMeasured));
        if (!heatOk && firstFailure == null) firstFailure = RejectReason.HEAT_LIMIT;

        // 7 CONCENTRATION — case-insensitive sector match
        long sameSectorCount = ctx.openPositions().stream()
                .filter(p -> p.sector() != null
                        && p.sector().equalsIgnoreCase(ctx.candidateSector()))
                .count();
        boolean concentrationOk = sameSectorCount < cfg.maxPerSector();
        String concentrationMeasured = sameSectorCount + (concentrationOk ? " < " : " >= ") + cfg.maxPerSector()
                + " in sector " + ctx.candidateSector();
        results.add(new VetoResult("CONCENTRATION", concentrationOk, concentrationMeasured));
        if (!concentrationOk && firstFailure == null) firstFailure = RejectReason.CONCENTRATION;

        // 8 CORRELATED — same sector AND same mechanism as an existing open position
        String candSector = ctx.candidateSector();
        String mech = signal == null ? null : signal.mechanism();
        ExecutorPosition correlatedMatch = (candSector == null || mech == null) ? null
                : ctx.openPositions().stream()
                        .filter(p -> candSector.equalsIgnoreCase(p.sector())
                                && mech.equalsIgnoreCase(ctx.openMechanisms().getOrDefault(p.symbol(), "")))
                        .findFirst().orElse(null);
        boolean uncorrelated = correlatedMatch == null;
        String correlatedMeasured = uncorrelated
                ? "no open position shares sector+mechanism"
                : "matches " + correlatedMatch.symbol() + " in sector " + candSector;
        results.add(new VetoResult("CORRELATED", uncorrelated, correlatedMeasured));
        if (!uncorrelated && firstFailure == null) firstFailure = RejectReason.CORRELATED;

        // 9 CONTRADICTION — MERGER_ARB vs {PEAD, SPINOFF, INSIDER_CLUSTER, INDEX_INCLUSION,
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
        String contradictionMeasured;
        if (contradictionOk) {
            contradictionMeasured = "no incompatible mechanism in book";
        } else if (contradictingSignalId != null) {
            contradictionMeasured = "conflicts with pending signal " + contradictingSignalId;
        } else {
            contradictionMeasured = "conflicts with open position mechanism "
                    + ctx.openMechanisms().get(signal.symbol());
        }
        results.add(new VetoResult("CONTRADICTION", contradictionOk, contradictionMeasured));
        if (!contradictionOk && firstFailure == null) firstFailure = RejectReason.CONTRADICTION;

        // 10 REDUNDANCY — same mechanism already open on the same symbol
        boolean redundancyOk = !(schemaOk
                && signal.mechanism().equals(ctx.openMechanisms().get(signal.symbol())));
        String redundancyMeasured = redundancyOk
                ? "no open position with same mechanism on symbol"
                : "mechanism " + signal.mechanism() + " already open on " + signal.symbol();
        results.add(new VetoResult("REDUNDANCY", redundancyOk, redundancyMeasured));
        if (!redundancyOk && firstFailure == null) firstFailure = RejectReason.REDUNDANCY;

        // 11 LIQUIDITY
        boolean priceOk = ctx.price().compareTo(cfg.minPrice()) >= 0;
        boolean advOk = ctx.adv20Notional().compareTo(
                ctx.trancheAmount().multiply(BigDecimal.valueOf(cfg.advMultiple()))) >= 0;
        boolean liquidityOk = priceOk && advOk;
        String liquidityMeasured = "price " + fmt2(ctx.price()) + (priceOk ? " >= " : " < ") + fmt2(cfg.minPrice())
                + ", adv " + (advOk ? "ok" : "insufficient");
        results.add(new VetoResult("LIQUIDITY", liquidityOk, liquidityMeasured));
        if (!liquidityOk && firstFailure == null) firstFailure = RejectReason.LIQUIDITY;

        // 12 SIGNAL_EXPIRED
        boolean expiredOk = ctx.signalAgeTradingDays() <= cfg.maxSignalAgeDays();
        String expiredMeasured = ctx.signalAgeTradingDays() + (expiredOk ? " <= " : " > ")
                + cfg.maxSignalAgeDays() + " days";
        results.add(new VetoResult("SIGNAL_EXPIRED", expiredOk, expiredMeasured));
        if (!expiredOk && firstFailure == null) firstFailure = RejectReason.SIGNAL_EXPIRED;

        // 13 CHASED_AWAY — unlike the other signal-dependent vetos above, this one is NOT gated by
        // schemaOk: a null signal, or a schema-valid signal with a null referencePrice (a field not
        // covered by SCHEMA_INVALID's checklist), can otherwise reach this line and NPE. Made total:
        // schema-invalid ⇒ traces PASS (consistent with the other gated vetos; SCHEMA_INVALID is
        // already firstFailure); schema-valid but referencePrice == null ⇒ FAILS conservatively (an
        // unverifiable chase check is not tradeable). In production the assembler already routes a
        // null reference price into the DATA_UNAVAILABLE pre-veto, so this branch is defense-in-depth
        // for direct/pure calls to evaluate().
        boolean chasedOk;
        String chasedMeasured;
        if (!schemaOk) {
            chasedOk = true;
            chasedMeasured = "not evaluated (schema invalid)";
        } else if (signal.referencePrice() == null) {
            chasedOk = false;
            chasedMeasured = "reference price unavailable";
        } else if ("SELL".equals(signal.direction())) {
            // Mirror for a short entry: chased away means price has already collapsed too far
            // BELOW the reference (the opposite direction of the BUY case below).
            BigDecimal chaseLimit = ctx.atr().multiply(BigDecimal.valueOf(cfg.chaseAtrMult()));
            BigDecimal chaseThreshold = signal.referencePrice().subtract(chaseLimit);
            chasedOk = ctx.price().compareTo(chaseThreshold) >= 0;
            // drift = adverse (downward) distance already travelled from the reference price;
            // chased away when it exceeds chaseAtrMult x ATR.
            BigDecimal drift = signal.referencePrice().subtract(ctx.price());
            chasedMeasured = "drift " + fmt2(drift) + (chasedOk ? " <= " : " > ")
                    + fmtMult(cfg.chaseAtrMult()) + "xATR " + fmt2(chaseLimit);
        } else {
            BigDecimal chaseLimit = ctx.atr().multiply(BigDecimal.valueOf(cfg.chaseAtrMult()));
            BigDecimal chaseThreshold = signal.referencePrice().add(chaseLimit);
            chasedOk = ctx.price().compareTo(chaseThreshold) <= 0;
            // drift = how far price has already run above the reference; chased away when it
            // exceeds chaseAtrMult x ATR.
            BigDecimal drift = ctx.price().subtract(signal.referencePrice());
            chasedMeasured = "drift " + fmt2(drift) + (chasedOk ? " <= " : " > ")
                    + fmtMult(cfg.chaseAtrMult()) + "xATR " + fmt2(chaseLimit);
        }
        results.add(new VetoResult("CHASED_AWAY", chasedOk, chasedMeasured));
        if (!chasedOk && firstFailure == null) firstFailure = RejectReason.CHASED_AWAY;

        // 14 BELOW_ANCHOR — adverse-side mirror of CHASED_AWAY. Not gated by schemaOk for the same
        // NPE-safety reason. C1: NO signal.* read before the guard (DRIFT_ANCHOR_MECHANISMS.contains(null)
        // and signal.mechanism() would NPE on the legal null-signal path). Compares the EFFECTIVE entry
        // price (min(orderPrice, market) long) against reference ± band; band = τ×ATR, τ drift=0 / value=3.
        boolean anchorOk;
        String anchorMeasured;
        if (!schemaOk) {
            anchorOk = true;  anchorMeasured = "not evaluated (schema invalid)";
        } else if (signal.referencePrice() == null) {
            anchorOk = false; anchorMeasured = "reference price unavailable";
        } else {
            String mechUpper = signal.mechanism().toUpperCase(Locale.ROOT); // schemaOk ⇒ non-blank
            double anchorAtrMult = DRIFT_ANCHOR_MECHANISMS.contains(mechUpper)
                    ? cfg.driftAnchorAtrMult() : cfg.valueAnchorAtrMult();
            BigDecimal market = ctx.price();
            BigDecimal effEntry = (orderPrice == null) ? market : orderPrice;
            BigDecimal band = ctx.atr().multiply(BigDecimal.valueOf(anchorAtrMult));
            if ("SELL".equals(signal.direction())) {
                BigDecimal eff = effEntry.max(market);
                BigDecimal threshold = signal.referencePrice().add(band);
                anchorOk = eff.compareTo(threshold) <= 0;
                BigDecimal drift = eff.subtract(signal.referencePrice());
                anchorMeasured = "adverse " + fmt2(drift) + (anchorOk ? " <= " : " > ") + fmtMult(anchorAtrMult) + "xATR " + fmt2(band);
            } else {
                BigDecimal eff = effEntry.min(market);
                BigDecimal threshold = signal.referencePrice().subtract(band);
                anchorOk = eff.compareTo(threshold) >= 0;
                BigDecimal drift = signal.referencePrice().subtract(eff);
                anchorMeasured = "adverse " + fmt2(drift) + (anchorOk ? " <= " : " > ") + fmtMult(anchorAtrMult) + "xATR " + fmt2(band);
            }
        }
        results.add(new VetoResult("BELOW_ANCHOR", anchorOk, anchorMeasured));
        if (!anchorOk && firstFailure == null) firstFailure = RejectReason.BELOW_ANCHOR;

        // 15 PACE_LIMIT
        boolean paceOk = ctx.entriesThisWeek() < cfg.pacePerWeek();
        String paceMeasured = ctx.entriesThisWeek() + (paceOk ? " < " : " >= ") + cfg.pacePerWeek() + " this week";
        results.add(new VetoResult("PACE_LIMIT", paceOk, paceMeasured));
        if (!paceOk && firstFailure == null) firstFailure = RejectReason.PACE_LIMIT;

        // 16 CURRENCY_MISMATCH — the executor is single-currency in this slice: it can only size a
        // bracket in the configured account/instrument currency (cfg.instrumentCurrency()). A find
        // whose instrument trades in another currency (EUR/JPY/HKD), or whose quote carried NO
        // currency at all (null — NOT silently coerced to USD upstream), must never be entered, or
        // the order would be mis-sized against the wrong currency. Such a find is still surfaced,
        // watchlisted and given a Verdict — only the bracket order is withheld. A full
        // multi-currency executor is a later project.
        String quoteCcy = ctx.quoteCurrency();
        boolean currencyOk = quoteCcy != null && quoteCcy.equalsIgnoreCase(cfg.instrumentCurrency());
        String currencyMeasured = (quoteCcy == null ? "unknown" : quoteCcy)
                + (currencyOk ? " == " : " != ") + cfg.instrumentCurrency();
        results.add(new VetoResult("CURRENCY_MISMATCH", currencyOk, currencyMeasured));
        if (!currencyOk && firstFailure == null) firstFailure = RejectReason.CURRENCY_MISMATCH;

        boolean passed = firstFailure == null;
        Snapshot snapshot = new Snapshot(heatBeforePct, usedPct, budgetFree, ctx.entriesThisWeek(),
                (int) sameSectorCount, cooldownMeasured);
        return new Outcome(passed, firstFailure, results, contradictingSignalId, snapshot);
    }

    /** Two-decimal formatting for measured-string amounts (account/instrument ccy). */
    private static String fmt2(BigDecimal v) {
        return v == null ? "n/a" : v.setScale(2, RoundingMode.HALF_UP).toString();
    }

    /** ATR-multiple formatting: whole values without trailing ".0" ({@code 1xATR}, not {@code 1.0xATR}). */
    private static String fmtMult(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private boolean isContradictingPair(String mechanismA, String mechanismB) {
        if (mechanismA == null || mechanismB == null) return false;
        return ("MERGER_ARB".equals(mechanismA) && MERGER_ARB_CONTRADICTIONS.contains(mechanismB))
                || ("MERGER_ARB".equals(mechanismB) && MERGER_ARB_CONTRADICTIONS.contains(mechanismA));
    }
}
