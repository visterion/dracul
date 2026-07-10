package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.AccountSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full 13-veto catalog + DATA_UNAVAILABLE pre-veto. {@link #ctx()}/{@link #sizing()}/
 * {@link #cfg()} return pass-everything defaults; each test perturbs exactly what it needs to
 * exercise one veto boundary.
 */
class VetoServiceTest {

    private final VetoService vetoService = new VetoService();

    // ---- defaults: every veto passes vacuously ----

    private ExecutorSignal signal() {
        return new ExecutorSignal(
                "sig-1", "strigoi-test", "v1", "ACME", "LONG", 0.8, "PEAD",
                List.of("Close below 90.00"), "20d", BigDecimal.valueOf(50), "PENDING",
                "2026-07-08T00:00:00Z");
    }

    private EntryContextBuilder ctx() {
        return new EntryContextBuilder();
    }

    private Sizing sizing() {
        return new Sizing(BigDecimal.TEN, BigDecimal.ONE, BigDecimal.valueOf(100),
                BigDecimal.ZERO, BigDecimal.ZERO, true);
    }

    private VetoConfig cfg() {
        return new VetoConfig(0.6, 5, BigDecimal.valueOf(10000), 0.06, 3,
                BigDecimal.valueOf(5), 20, 5, 2.0, 3);
    }

    /** Fluent builder producing {@link EntryContext} with pass-everything defaults. */
    private static class EntryContextBuilder {
        AccountSnapshot account = new AccountSnapshot(BigDecimal.valueOf(100000),
                BigDecimal.valueOf(100000), "USD");
        BigDecimal price = BigDecimal.valueOf(50);
        BigDecimal atr = BigDecimal.valueOf(2);
        BigDecimal swingLow = null;
        BigDecimal adv20Notional = BigDecimal.valueOf(1_000_000);
        BigDecimal dayHigh = null;
        String candidateSector = "Tech";
        List<ExecutorPosition> openPositions = List.of();
        List<Cooldown> activeCooldowns = List.of();
        List<ExecutorSignal> pendingSignals = List.of();
        int entriesThisWeek = 0;
        long signalAgeTradingDays = 1;
        BigDecimal trancheAmount = BigDecimal.valueOf(1000);
        BigDecimal totalBudget = BigDecimal.valueOf(10000);
        BigDecimal openExposure = BigDecimal.ZERO;
        BigDecimal openHeat = BigDecimal.ZERO;
        Map<String, String> openMechanisms = Map.of();
        BigDecimal fxToAccount = BigDecimal.ONE;
        List<String> missing = List.of();

        EntryContextBuilder account(AccountSnapshot v) { account = v; return this; }
        EntryContextBuilder price(BigDecimal v) { price = v; return this; }
        EntryContextBuilder atr(BigDecimal v) { atr = v; return this; }
        EntryContextBuilder adv20Notional(BigDecimal v) { adv20Notional = v; return this; }
        EntryContextBuilder candidateSector(String v) { candidateSector = v; return this; }
        EntryContextBuilder openPositions(List<ExecutorPosition> v) { openPositions = v; return this; }
        EntryContextBuilder activeCooldowns(List<Cooldown> v) { activeCooldowns = v; return this; }
        EntryContextBuilder pendingSignals(List<ExecutorSignal> v) { pendingSignals = v; return this; }
        EntryContextBuilder entriesThisWeek(int v) { entriesThisWeek = v; return this; }
        EntryContextBuilder signalAgeTradingDays(long v) { signalAgeTradingDays = v; return this; }
        EntryContextBuilder trancheAmount(BigDecimal v) { trancheAmount = v; return this; }
        EntryContextBuilder openExposure(BigDecimal v) { openExposure = v; return this; }
        EntryContextBuilder openHeat(BigDecimal v) { openHeat = v; return this; }
        EntryContextBuilder openMechanisms(Map<String, String> v) { openMechanisms = v; return this; }
        EntryContextBuilder missing(List<String> v) { missing = v; return this; }

        EntryContext build() {
            return new EntryContext(account, price, atr, swingLow, adv20Notional, dayHigh,
                    candidateSector, openPositions, activeCooldowns, pendingSignals,
                    entriesThisWeek, signalAgeTradingDays, trancheAmount, totalBudget,
                    openExposure, openHeat, openMechanisms, fxToAccount, missing);
        }
    }

    private ExecutorPosition position(String symbol, String sector) {
        return new ExecutorPosition(1L, "sim", symbol, "LONG", BigDecimal.TEN,
                BigDecimal.valueOf(50), BigDecimal.valueOf(45), BigDecimal.valueOf(45), 1,
                BigDecimal.ONE, List.of("kill"), "src-sig", "agent", "2026-07-01",
                BigDecimal.ZERO, "OPEN", null, BigDecimal.valueOf(50), BigDecimal.ZERO, 0,
                null, null, null, null, null, sector, null, null, null);
    }

    private ExecutorSignal pending(String signalId, String symbol, String mechanism) {
        return new ExecutorSignal(signalId, "strigoi-test", "v1", symbol, "LONG", 0.8, mechanism,
                List.of("kill"), "20d", BigDecimal.valueOf(50), "PENDING",
                "2026-07-08T00:00:00Z");
    }

    // ---- happy path ----

    @Test
    void allVetosPassByDefault() {
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx().build(), sizing(), cfg());

        assertThat(outcome.passed()).isTrue();
        assertThat(outcome.firstFailure()).isNull();
        assertThat(outcome.results()).hasSize(13);
        assertThat(outcome.results()).allMatch(VetoResult::passed);
        assertThat(outcome.contradictingSignalId()).isNull();
    }

    // ---- pre-veto: DATA_UNAVAILABLE ----

    @Test
    void dataUnavailableShortCircuits() {
        EntryContext ctx = ctx().missing(List.of("price")).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.DATA_UNAVAILABLE);
        assertThat(outcome.results()).hasSize(1);
        assertThat(outcome.results().get(0).check()).isEqualTo("DATA_UNAVAILABLE:price");
    }

    @Test
    void dataUnavailableJoinsMultipleNames() {
        EntryContext ctx = ctx().missing(List.of("price", "atr")).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(outcome.results().get(0).check()).isEqualTo("DATA_UNAVAILABLE:price,atr");
    }

    // ---- 1 SCHEMA_INVALID ----

    @Test
    void schemaInvalid_nullSymbol() {
        ExecutorSignal sig = new ExecutorSignal("sig-1", "s", "v1", null, "LONG", 0.8, "PEAD",
                List.of("kill"), "20d", BigDecimal.valueOf(50), "PENDING", "2026-07-08T00:00:00Z");
        VetoService.Outcome outcome = vetoService.evaluate(sig, ctx().build(), sizing(), cfg());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.SCHEMA_INVALID);
    }

    @Test
    void schemaInvalid_blankMechanism() {
        ExecutorSignal sig = new ExecutorSignal("sig-1", "s", "v1", "ACME", "LONG", 0.8, "  ",
                List.of("kill"), "20d", BigDecimal.valueOf(50), "PENDING", "2026-07-08T00:00:00Z");
        VetoService.Outcome outcome = vetoService.evaluate(sig, ctx().build(), sizing(), cfg());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.SCHEMA_INVALID);
    }

    @Test
    void schemaInvalid_blankAgentVersion() {
        ExecutorSignal sig = new ExecutorSignal("sig-1", "s", "", "ACME", "LONG", 0.8, "PEAD",
                List.of("kill"), "20d", BigDecimal.valueOf(50), "PENDING", "2026-07-08T00:00:00Z");
        VetoService.Outcome outcome = vetoService.evaluate(sig, ctx().build(), sizing(), cfg());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.SCHEMA_INVALID);
    }

    @Test
    void schemaInvalid_emptyKillCriteria() {
        ExecutorSignal sig = new ExecutorSignal("sig-1", "s", "v1", "ACME", "LONG", 0.8, "PEAD",
                List.of(), "20d", BigDecimal.valueOf(50), "PENDING", "2026-07-08T00:00:00Z");
        VetoService.Outcome outcome = vetoService.evaluate(sig, ctx().build(), sizing(), cfg());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.SCHEMA_INVALID);
    }

    @Test
    void schemaValid_pass() {
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx().build(), sizing(), cfg());
        VetoResult r = result(outcome, "SCHEMA_INVALID");
        assertThat(r.passed()).isTrue();
    }

    // ---- 2 LOW_CONFIDENCE ----

    @Test
    void lowConfidence_fail() {
        ExecutorSignal sig = new ExecutorSignal("sig-1", "s", "v1", "ACME", "LONG", 0.4, "PEAD",
                List.of("kill"), "20d", BigDecimal.valueOf(50), "PENDING", "2026-07-08T00:00:00Z");
        VetoService.Outcome outcome = vetoService.evaluate(sig, ctx().build(), sizing(), cfg());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.LOW_CONFIDENCE);
    }

    @Test
    void lowConfidence_boundaryEqualsThresholdPasses() {
        ExecutorSignal sig = new ExecutorSignal("sig-1", "s", "v1", "ACME", "LONG", 0.6, "PEAD",
                List.of("kill"), "20d", BigDecimal.valueOf(50), "PENDING", "2026-07-08T00:00:00Z");
        VetoService.Outcome outcome = vetoService.evaluate(sig, ctx().build(), sizing(), cfg());

        assertThat(result(outcome, "LOW_CONFIDENCE").passed()).isTrue();
    }

    // ---- 3 COOLDOWN ----

    @Test
    void cooldown_activeMatchingSymbol_fails() {
        Cooldown cd = new Cooldown(1L, "ACME", "stopped out", "2026-08-01", null, "2026-07-01");
        EntryContext ctx = ctx().activeCooldowns(List.of(cd)).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.COOLDOWN);
    }

    @Test
    void cooldown_differentSymbol_passes() {
        Cooldown cd = new Cooldown(1L, "OTHER", "stopped out", "2026-08-01", null, "2026-07-01");
        EntryContext ctx = ctx().activeCooldowns(List.of(cd)).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(result(outcome, "COOLDOWN").passed()).isTrue();
    }

    @Test
    void cooldown_activeRow_alwaysFails() {
        // v1 has no fresh-setup exception: the cooldown's origin mechanism is not stored anywhere,
        // so an exceptionCondition on the row (and/or a differing open mechanism) can never be
        // verified against the mechanism that actually triggered the cooldown. Any active cooldown
        // row matching the symbol is therefore a hard block, regardless of exceptionCondition or
        // the state of openMechanisms.
        Cooldown cd = new Cooldown(1L, "ACME", "stopped out", "2026-08-01",
                "fresh catalyst", "2026-07-01");
        EntryContext ctx = ctx().activeCooldowns(List.of(cd))
                .openMechanisms(Map.of("ACME", "SPINOFF"))
                .build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.COOLDOWN);
    }

    // ---- 4 MAX_POSITIONS ----

    @Test
    void maxPositions_atLimit_fails() {
        List<ExecutorPosition> open = List.of(position("A", "Tech"), position("B", "Tech"),
                position("C", "Tech"), position("D", "Tech"), position("E", "Tech"));
        EntryContext ctx = ctx().openPositions(open).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.MAX_POSITIONS);
    }

    @Test
    void maxPositions_belowLimit_passes() {
        List<ExecutorPosition> open = List.of(position("A", "Tech"), position("B", "Tech"));
        EntryContext ctx = ctx().openPositions(open).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(result(outcome, "MAX_POSITIONS").passed()).isTrue();
    }

    // ---- 5 BUDGET ----

    @Test
    void budget_cashBelowTranche_fails() {
        // totalBudget 10000 -> tranche = 1000; cash 500 < 1000
        AccountSnapshot acc = new AccountSnapshot(BigDecimal.valueOf(500), BigDecimal.valueOf(500), "USD");
        EntryContext ctx = ctx().account(acc).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.BUDGET);
    }

    @Test
    void budget_exposurePlusTrancheExceedsTotal_fails() {
        // openExposure 9500 + tranche 1000 = 10500 > 10000
        EntryContext ctx = ctx().openExposure(BigDecimal.valueOf(9500)).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.BUDGET);
    }

    @Test
    void budget_boundaryExactlyAtTotal_passes() {
        // openExposure 9000 + tranche 1000 = 10000, not > 10000 -> pass
        EntryContext ctx = ctx().openExposure(BigDecimal.valueOf(9000)).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(result(outcome, "BUDGET").passed()).isTrue();
    }

    @Test
    void budget_cashExactlyAtTranche_passes() {
        AccountSnapshot acc = new AccountSnapshot(BigDecimal.valueOf(1000), BigDecimal.valueOf(1000), "USD");
        EntryContext ctx = ctx().account(acc).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(result(outcome, "BUDGET").passed()).isTrue();
    }

    // ---- 6 HEAT_LIMIT ----

    @Test
    void heatLimit_fails() {
        // heatPct 0.06 * totalBudget 10000 = 600 limit. openHeat 550 + newRisk 100 = 650 > 600.
        EntryContext ctx = ctx().openHeat(BigDecimal.valueOf(550)).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.HEAT_LIMIT);
    }

    @Test
    void heatLimit_boundaryExactlyAtLimit_passes() {
        // openHeat 500 + newRisk 100 = 600, not > 600 -> pass
        EntryContext ctx = ctx().openHeat(BigDecimal.valueOf(500)).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(result(outcome, "HEAT_LIMIT").passed()).isTrue();
    }

    // ---- 7 CONCENTRATION ----

    @Test
    void concentration_atLimit_fails() {
        List<ExecutorPosition> open = List.of(position("A", "Tech"), position("B", "Tech"),
                position("C", "Tech"));
        EntryContext ctx = ctx().openPositions(open).candidateSector("Tech").build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.CONCENTRATION);
    }

    @Test
    void concentration_caseInsensitiveSectorMatch_fails() {
        List<ExecutorPosition> open = List.of(position("A", "TECH"), position("B", "tech"),
                position("C", "Tech"));
        EntryContext ctx = ctx().openPositions(open).candidateSector("Tech").build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(result(outcome, "CONCENTRATION").passed()).isFalse();
    }

    @Test
    void concentration_belowLimit_passes() {
        List<ExecutorPosition> open = List.of(position("A", "Tech"), position("B", "Tech"));
        EntryContext ctx = ctx().openPositions(open).candidateSector("Tech").build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(result(outcome, "CONCENTRATION").passed()).isTrue();
    }

    @Test
    void concentration_differentSector_notCounted() {
        List<ExecutorPosition> open = List.of(position("A", "Energy"), position("B", "Energy"),
                position("C", "Energy"));
        EntryContext ctx = ctx().openPositions(open).candidateSector("Tech").build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(result(outcome, "CONCENTRATION").passed()).isTrue();
    }

    // ---- 8 CONTRADICTION ----

    @Test
    void contradiction_pendingMergerArbVsPead_setsContradictingSignalId() {
        // candidate signal is MERGER_ARB on ACME; a pending PEAD signal exists on the same symbol.
        ExecutorSignal candidate = new ExecutorSignal("sig-1", "s", "v1", "ACME", "LONG", 0.8,
                "MERGER_ARB", List.of("kill"), "20d", BigDecimal.valueOf(50), "PENDING",
                "2026-07-08T00:00:00Z");
        ExecutorSignal contradicting = pending("sig-2", "ACME", "PEAD");
        EntryContext ctx = ctx().pendingSignals(List.of(contradicting)).build();
        VetoService.Outcome outcome = vetoService.evaluate(candidate, ctx, sizing(), cfg());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.CONTRADICTION);
        assertThat(outcome.contradictingSignalId()).isEqualTo("sig-2");
    }

    @Test
    void contradiction_reverseDirection_peadCandidateVsPendingMergerArb() {
        ExecutorSignal candidate = new ExecutorSignal("sig-1", "s", "v1", "ACME", "LONG", 0.8,
                "PEAD", List.of("kill"), "20d", BigDecimal.valueOf(50), "PENDING",
                "2026-07-08T00:00:00Z");
        ExecutorSignal contradicting = pending("sig-2", "ACME", "MERGER_ARB");
        EntryContext ctx = ctx().pendingSignals(List.of(contradicting)).build();
        VetoService.Outcome outcome = vetoService.evaluate(candidate, ctx, sizing(), cfg());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.contradictingSignalId()).isEqualTo("sig-2");
    }

    @Test
    void contradiction_vsOpenPositionMechanism_fails() {
        ExecutorSignal candidate = new ExecutorSignal("sig-1", "s", "v1", "ACME", "LONG", 0.8,
                "MERGER_ARB", List.of("kill"), "20d", BigDecimal.valueOf(50), "PENDING",
                "2026-07-08T00:00:00Z");
        EntryContext ctx = ctx().openMechanisms(Map.of("ACME", "SPINOFF")).build();
        VetoService.Outcome outcome = vetoService.evaluate(candidate, ctx, sizing(), cfg());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.CONTRADICTION);
    }

    @Test
    void contradiction_unrelatedMechanismPair_passes() {
        ExecutorSignal candidate = new ExecutorSignal("sig-1", "s", "v1", "ACME", "LONG", 0.8,
                "PEAD", List.of("kill"), "20d", BigDecimal.valueOf(50), "PENDING",
                "2026-07-08T00:00:00Z");
        EntryContext ctx = ctx().openMechanisms(Map.of("ACME", "SPINOFF")).build();
        VetoService.Outcome outcome = vetoService.evaluate(candidate, ctx, sizing(), cfg());

        // SPINOFF vs PEAD is not a MERGER_ARB pair -> CONTRADICTION passes (REDUNDANCY unaffected
        // since mechanisms differ too).
        assertThat(result(outcome, "CONTRADICTION").passed()).isTrue();
    }

    @Test
    void contradiction_differentSymbol_passes() {
        ExecutorSignal candidate = new ExecutorSignal("sig-1", "s", "v1", "ACME", "LONG", 0.8,
                "MERGER_ARB", List.of("kill"), "20d", BigDecimal.valueOf(50), "PENDING",
                "2026-07-08T00:00:00Z");
        ExecutorSignal contradicting = pending("sig-2", "OTHER", "PEAD");
        EntryContext ctx = ctx().pendingSignals(List.of(contradicting)).build();
        VetoService.Outcome outcome = vetoService.evaluate(candidate, ctx, sizing(), cfg());

        assertThat(result(outcome, "CONTRADICTION").passed()).isTrue();
        assertThat(outcome.contradictingSignalId()).isNull();
    }

    // ---- 9 REDUNDANCY ----

    @Test
    void redundancy_sameMechanismSameSymbol_fails() {
        EntryContext ctx = ctx().openMechanisms(Map.of("ACME", "PEAD")).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.REDUNDANCY);
    }

    @Test
    void redundancy_differentMechanism_passes() {
        EntryContext ctx = ctx().openMechanisms(Map.of("ACME", "SPINOFF")).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(result(outcome, "REDUNDANCY").passed()).isTrue();
    }

    @Test
    void redundancy_noOpenMechanismForSymbol_passes() {
        EntryContext ctx = ctx().openMechanisms(Map.of("OTHER", "PEAD")).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(result(outcome, "REDUNDANCY").passed()).isTrue();
    }

    // ---- 10 LIQUIDITY ----

    @Test
    void liquidity_priceBelowMin_fails() {
        EntryContext ctx = ctx().price(BigDecimal.valueOf(4)).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.LIQUIDITY);
    }

    @Test
    void liquidity_priceExactlyAtMin_passes() {
        EntryContext ctx = ctx().price(BigDecimal.valueOf(5)).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(result(outcome, "LIQUIDITY").passed()).isTrue();
    }

    @Test
    void liquidity_advBelowMultipleOfTranche_fails() {
        // advMultiple 20 * trancheAmount 1000 = 20000; adv20Notional 19999 < 20000 -> fail
        EntryContext ctx = ctx().adv20Notional(BigDecimal.valueOf(19999)).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.LIQUIDITY);
    }

    @Test
    void liquidity_advExactlyAtMultiple_passes() {
        EntryContext ctx = ctx().adv20Notional(BigDecimal.valueOf(20000)).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(result(outcome, "LIQUIDITY").passed()).isTrue();
    }

    // ---- 11 SIGNAL_EXPIRED ----

    @Test
    void signalExpired_overMax_fails() {
        EntryContext ctx = ctx().signalAgeTradingDays(6).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.SIGNAL_EXPIRED);
    }

    @Test
    void signalExpired_boundaryExactlyAtMax_passes() {
        EntryContext ctx = ctx().signalAgeTradingDays(5).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(result(outcome, "SIGNAL_EXPIRED").passed()).isTrue();
    }

    // ---- 12 CHASED_AWAY ----

    @Test
    void chasedAway_priceRunAway_fails() {
        // referencePrice 50, chaseAtrMult 2 * atr 2 = 4 -> threshold 54. price 55 > 54 -> fail
        EntryContext ctx = ctx().price(BigDecimal.valueOf(55)).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.CHASED_AWAY);
    }

    @Test
    void chasedAway_boundaryExactlyAtThreshold_passes() {
        EntryContext ctx = ctx().price(BigDecimal.valueOf(54)).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(result(outcome, "CHASED_AWAY").passed()).isTrue();
    }

    @Test
    void chasedAway_nullReferencePrice_failsConservatively() {
        // Schema-valid signal (referencePrice is not part of the SCHEMA_INVALID checklist), but
        // referencePrice is null -> the chase check is unverifiable -> fail conservative rather
        // than NPE.
        ExecutorSignal sig = new ExecutorSignal("sig-1", "strigoi-test", "v1", "ACME", "LONG", 0.8,
                "PEAD", List.of("kill"), "20d", null, "PENDING", "2026-07-08T00:00:00Z");
        VetoService.Outcome outcome = vetoService.evaluate(sig, ctx().build(), sizing(), cfg());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.CHASED_AWAY);
    }

    @Test
    void chasedAway_schemaInvalid_notEvaluated() {
        // Signal missing mechanism -> SCHEMA_INVALID is firstFailure; CHASED_AWAY (not gated by
        // schemaOk in the null-signal sense but internally short-circuited) must still trace PASS,
        // not throw and not become firstFailure.
        ExecutorSignal sig = new ExecutorSignal("sig-1", "s", "v1", "ACME", "LONG", 0.8, null,
                List.of("kill"), "20d", BigDecimal.valueOf(50), "PENDING", "2026-07-08T00:00:00Z");
        VetoService.Outcome outcome = vetoService.evaluate(sig, ctx().build(), sizing(), cfg());

        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.SCHEMA_INVALID);
        assertThat(result(outcome, "CHASED_AWAY").passed()).isTrue();
    }

    @Test
    void evaluate_nullSignal_noException() {
        VetoService.Outcome outcome = vetoService.evaluate(null, ctx().build(), sizing(), cfg());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.SCHEMA_INVALID);
        assertThat(result(outcome, "CHASED_AWAY").passed()).isTrue();
    }

    // ---- 13 PACE_LIMIT ----

    @Test
    void paceLimit_atLimit_fails() {
        EntryContext ctx = ctx().entriesThisWeek(3).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.PACE_LIMIT);
    }

    @Test
    void paceLimit_belowLimit_passes() {
        EntryContext ctx = ctx().entriesThisWeek(2).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(result(outcome, "PACE_LIMIT").passed()).isTrue();
    }

    // ---- full trace + ordering ----

    @Test
    void allThirteenVetosAlwaysEvaluated_evenAfterFirstFailure() {
        EntryContext ctx = ctx().entriesThisWeek(3).build(); // fails PACE_LIMIT (last veto)
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(outcome.results()).hasSize(13);
        assertThat(outcome.results().get(12).check()).isEqualTo("PACE_LIMIT");
    }

    @Test
    void firstFailureOrdering_lowConfidenceBeatsPaceLimit() {
        ExecutorSignal sig = new ExecutorSignal("sig-1", "s", "v1", "ACME", "LONG", 0.4, "PEAD",
                List.of("kill"), "20d", BigDecimal.valueOf(50), "PENDING", "2026-07-08T00:00:00Z");
        EntryContext ctx = ctx().entriesThisWeek(3).build(); // also fails PACE_LIMIT
        VetoService.Outcome outcome = vetoService.evaluate(sig, ctx, sizing(), cfg());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.LOW_CONFIDENCE);
        assertThat(result(outcome, "PACE_LIMIT").passed()).isFalse();
    }

    @Test
    void resultsPreserveSpecOrder() {
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx().build(), sizing(), cfg());

        List<String> expectedOrder = List.of("SCHEMA_INVALID", "LOW_CONFIDENCE", "COOLDOWN",
                "MAX_POSITIONS", "BUDGET", "HEAT_LIMIT", "CONCENTRATION", "CONTRADICTION",
                "REDUNDANCY", "LIQUIDITY", "SIGNAL_EXPIRED", "CHASED_AWAY", "PACE_LIMIT");
        List<String> actualOrder = outcome.results().stream().map(VetoResult::check).toList();

        assertThat(actualOrder).isEqualTo(expectedOrder);
    }

    private VetoResult result(VetoService.Outcome outcome, String check) {
        return outcome.results().stream()
                .filter(r -> r.check().equals(check))
                .findFirst().orElseThrow();
    }
}
