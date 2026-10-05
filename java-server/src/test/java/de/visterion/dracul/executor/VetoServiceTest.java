package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.AccountSnapshot;
import de.visterion.dracul.pattern.EnforcedGate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full 18-veto catalog (incl. CURRENCY_MISMATCH + PATTERN_GATE + MECHANISM_BUDGET) + DATA_UNAVAILABLE pre-veto. {@link #ctx()}/{@link #sizing()}/
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

    private SignalBuilder signalBuilder() {
        return new SignalBuilder();
    }

    /** Fluent builder producing a schema-valid {@link ExecutorSignal} with pass-everything
     *  defaults (mirrors {@link #signal()}), for tests that need to vary mechanism/direction/
     *  referencePrice independently. */
    private static class SignalBuilder {
        String signalId = "sig-1";
        String source = "strigoi-test";
        String agentVersion = "v1";
        String symbol = "ACME";
        String direction = "LONG";
        Double confidence = 0.8;
        String mechanism = "PEAD";
        List<String> killCriteria = List.of("Close below 90.00");
        String horizon = "20d";
        BigDecimal referencePrice = BigDecimal.valueOf(50);
        String status = "PENDING";
        String createdAt = "2026-07-08T00:00:00Z";

        SignalBuilder mechanism(String v) { mechanism = v; return this; }
        SignalBuilder direction(String v) { direction = v; return this; }
        SignalBuilder referencePrice(BigDecimal v) { referencePrice = v; return this; }
        SignalBuilder confidence(Double v) { confidence = v; return this; }

        ExecutorSignal build() {
            return new ExecutorSignal(signalId, source, agentVersion, symbol, direction, confidence,
                    mechanism, killCriteria, horizon, referencePrice, status, createdAt);
        }
    }

    private Sizing sizing() {
        return new Sizing(BigDecimal.TEN, BigDecimal.ONE, BigDecimal.valueOf(100),
                BigDecimal.ZERO, BigDecimal.ZERO, true, "entry - 2.5 x ATR22",
                BigDecimal.TEN, BigDecimal.TEN, "NOTIONAL", null);
    }

    private VetoConfig cfg() {
        return new VetoConfig(0.6, 5, BigDecimal.valueOf(10000), 0.06, 3,
                BigDecimal.valueOf(5), 20, 5, 2.0, 3, 10, 0.0, 3.0, "USD", MechanismBudget.none());
    }

    private VetoConfig cfg(int trancheCount) {
        return new VetoConfig(0.6, 5, BigDecimal.valueOf(10000), 0.06, 3,
                BigDecimal.valueOf(5), 20, 5, 2.0, 3, trancheCount, 0.0, 3.0, "USD", MechanismBudget.none());
    }

    /** The four capital knobs (totalBudget, trancheCount, heatPct, pacePerWeek) are at their
     *  exec-v0.7 production defaults (100000 / 25 -> tranche 4000, 0.15, 10); every other field
     *  is the {@link #cfg()} test baseline. maxPositions and maxPerSector are set high here so
     *  this cfg isolates the BUDGET gate -- MAX_POSITIONS/MAX_PER_SECTOR are exercised by their
     *  own tests above with their own cfg(). */
    private VetoConfig cfgWithProdCapital() {
        return new VetoConfig(0.6, 100, BigDecimal.valueOf(100000), 0.15, 100,
                BigDecimal.valueOf(5), 20, 5, 2.0, 10, 25, 0.0, 3.0, "USD", MechanismBudget.none());
    }

    private VetoConfig cfgWithBudget(String spec, int maxPositions) {
        return new VetoConfig(0.6, maxPositions, BigDecimal.valueOf(10000), 0.06, 3,
                BigDecimal.valueOf(5), 20, 5, 2.0, 3, 10, 0.0, 3.0, "USD", new MechanismBudget(spec));
    }

    private static final String MERGER_SPEC = "MERGER_ARB:0.20,QUALITY_52W_LOW:0.15";

    private VetoResult named(VetoService.Outcome out, String check) {
        return out.results().stream().filter(v -> v.check().equals(check)).findFirst().orElseThrow();
    }

    /** True if BELOW_ANCHOR passes for this signal at the given market/order price + ATR. */
    private boolean belowAnchorPasses(ExecutorSignal s, double market, double orderPrice, double atr) {
        var out = vetoService.evaluate(s, ctx().price(BigDecimal.valueOf(market)).atr(BigDecimal.valueOf(atr)).build(),
                sizing(), cfg(), BigDecimal.valueOf(orderPrice));
        return named(out, "BELOW_ANCHOR").passed();
    }

    @Test
    void vetoConfig_anchorMultipliers_bindInDeclaredOrder() {
        VetoConfig c = new VetoConfig(0.6, 5, BigDecimal.valueOf(10000), 0.06, 3,
                BigDecimal.valueOf(5), 20, 5, 2.0, 3, 10, /*drift*/ 0.7, /*value*/ 3.3, "USD", MechanismBudget.none());
        assertThat(c.driftAnchorAtrMult()).isEqualTo(0.7);
        assertThat(c.valueAnchorAtrMult()).isEqualTo(3.3);
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
        String quoteCurrency = "USD";
        Map<String, BigDecimal> openExposureByMechanism = Map.of();

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
        EntryContextBuilder quoteCurrency(String v) { quoteCurrency = v; return this; }
        EntryContextBuilder openExposureByMechanism(Map<String, BigDecimal> v) { openExposureByMechanism = v; return this; }

        EntryContext build() {
            return new EntryContext(account, price, atr, swingLow, adv20Notional, dayHigh,
                    candidateSector, openPositions, activeCooldowns, pendingSignals,
                    entriesThisWeek, signalAgeTradingDays, trancheAmount, totalBudget,
                    openExposure, openHeat, openMechanisms, fxToAccount, missing, quoteCurrency,
                    null, atr, openExposureByMechanism, null);
        }
    }

    private ExecutorPosition position(String symbol, String sector) {
        return ExecutorPositionFixtures.withoutKillLevel(1L, "sim", symbol, "LONG", BigDecimal.TEN,
                BigDecimal.valueOf(50), BigDecimal.valueOf(45), BigDecimal.valueOf(45), 1,
                BigDecimal.ONE, List.of("kill"), "src-sig", "agent", "2026-07-01",
                BigDecimal.ZERO, "OPEN", null, BigDecimal.valueOf(50), BigDecimal.ZERO, 0,
                null, null, null, null, null, sector, null, null, null, 0, null, null,
                null, null, null, null, false, null, null);
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
        assertThat(outcome.results()).hasSize(18);
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

    // ---- pre-veto: SIGNAL_EXPIRED bounds a permanently data-less signal (I1 fix) ----

    @Test
    void dataUnavailable_butAlsoExpired_reportsSignalExpiredFirst() {
        // A permanently data-less instrument (e.g. no sector from Agora) whose signal is also
        // older than max-signal-age-days must retire via SIGNAL_EXPIRED, not linger PENDING
        // forever on DATA_UNAVAILABLE.
        EntryContext ctx = ctx().missing(List.of("sector")).signalAgeTradingDays(6).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.SIGNAL_EXPIRED);
        assertThat(outcome.contradictingSignalId()).isNull();
        assertThat(outcome.results()).hasSize(2);
        assertThat(outcome.results().get(0).check()).isEqualTo("SIGNAL_EXPIRED:FAIL (6 > 5 days)");
        assertThat(outcome.results().get(1).check()).isEqualTo("DATA_UNAVAILABLE:sector");
    }

    @Test
    void dataUnavailable_notYetExpired_staysDataUnavailable() {
        EntryContext ctx = ctx().missing(List.of("sector")).signalAgeTradingDays(2).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.DATA_UNAVAILABLE);
        assertThat(outcome.results()).hasSize(1);
        assertThat(outcome.results().get(0).check()).isEqualTo("DATA_UNAVAILABLE:sector");
    }

    @Test
    void dataUnavailable_unparseableCreatedAt_neverExpiresViaMissingAge() {
        // signal_age itself in ctx.missing() means createdAt was unparseable (age == -1); the
        // expiry check must never fire off that sentinel, so DATA_UNAVAILABLE stands regardless
        // of the (meaningless) age value.
        EntryContext ctx = ctx().missing(List.of("signal_age")).signalAgeTradingDays(-1).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.DATA_UNAVAILABLE);
        assertThat(outcome.results()).hasSize(1);
        assertThat(outcome.results().get(0).check()).isEqualTo("DATA_UNAVAILABLE:signal_age");
    }

    @Test
    void signalExpired_noMissingData_stillFiresAtCatalogCheckThree() {
        // Regression: the catalog's own SIGNAL_EXPIRED check (#3) is unaffected when there is no
        // missing data at all — the pre-veto branch is simply never entered.
        EntryContext ctx = ctx().signalAgeTradingDays(6).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.SIGNAL_EXPIRED);
        assertThat(outcome.results()).hasSize(18);
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

    @Test
    void budget_trancheCountHonored_smallerTrancheCountFailsSoonerAtSameExposure() {
        // trancheCount 5 -> tranche = 10000/5 = 2000; openExposure 8500 + 2000 = 10500 > 10000
        // (with the default trancheCount 10 -> tranche 1000, this same exposure would pass:
        // 8500 + 1000 = 9500 <= 10000).
        EntryContext ctx = ctx().openExposure(BigDecimal.valueOf(8500)).build();
        VetoService.Outcome outcomeDefault = vetoService.evaluate(signal(), ctx, sizing(), cfg());
        assertThat(result(outcomeDefault, "BUDGET").passed()).isTrue();

        VetoService.Outcome outcomeTranche5 = vetoService.evaluate(signal(), ctx, sizing(), cfg(5));
        assertThat(outcomeTranche5.passed()).isFalse();
        assertThat(outcomeTranche5.firstFailure()).isEqualTo(RejectReason.BUDGET);
    }

    @Test
    void budget_prodDefaults_25TranchesFillBudgetExactly_26thViolates() {
        // exec-v0.7 defaults: totalBudget 100000, trancheCount 25 -> tranche 100000/25 = 4000.
        // 24 tranches already open (96000) + this entry's tranche (4000) = 100000, exactly at
        // budget -> passes (BUDGET is a <=, not a strict <).
        EntryContext ctxAt24Tranches = ctx().openExposure(BigDecimal.valueOf(96000)).build();
        VetoService.Outcome atLimit = vetoService.evaluate(signal(), ctxAt24Tranches, sizing(), cfgWithProdCapital());
        assertThat(result(atLimit, "BUDGET").passed()).isTrue();

        // 25 tranches already open (100000) + a 26th tranche (4000) = 104000 > 100000 -> BUDGET fails.
        EntryContext ctxAt25Tranches = ctx().openExposure(BigDecimal.valueOf(100000)).build();
        VetoService.Outcome overLimit = vetoService.evaluate(signal(), ctxAt25Tranches, sizing(), cfgWithProdCapital());
        assertThat(overLimit.passed()).isFalse();
        assertThat(overLimit.firstFailure()).isEqualTo(RejectReason.BUDGET);
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

    // ---- 8 CORRELATED ----

    @Test
    void correlated_rejectsSameSectorSameMechanism() {
        ExecutorSignal s = signal(); // default mechanism "PEAD"
        EntryContext ctx = ctx()
                .openPositions(List.of(position("XYZ", "Technology")))
                .openMechanisms(Map.of("XYZ", s.mechanism()))
                .candidateSector("Technology")
                .build();
        VetoService.Outcome outcome = vetoService.evaluate(s, ctx, sizing(), cfg());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.CORRELATED);
    }

    @Test
    void correlated_passesWhenSectorDiffers() {
        ExecutorSignal s = signal();
        EntryContext ctx = ctx()
                .openPositions(List.of(position("XYZ", "Energy")))
                .openMechanisms(Map.of("XYZ", s.mechanism()))
                .candidateSector("Technology")
                .build();

        assertThat(vetoService.evaluate(s, ctx, sizing(), cfg()).passed()).isTrue();
    }

    @Test
    void correlated_passesWhenMechanismDiffers() {
        ExecutorSignal s = signal();
        EntryContext ctx = ctx()
                .openPositions(List.of(position("XYZ", "Technology")))
                .openMechanisms(Map.of("XYZ", "SOMETHING_ELSE"))
                .candidateSector("Technology")
                .build();

        assertThat(vetoService.evaluate(s, ctx, sizing(), cfg()).passed()).isTrue();
    }

    // ---- 9 CONTRADICTION ----

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

    // ---- 10 REDUNDANCY ----

    @Test
    void redundancy_sameMechanismSameSymbol_fails() {
        EntryContext ctx = ctx().openPositions(List.of(position("ACME", null)))
                .openMechanisms(Map.of("ACME", "PEAD")).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.REDUNDANCY);
    }

    @Test
    void redundancy_failsWhenAnotherMechanismIsOpenOnTheSymbol() {
        EntryContext ctx = ctx().openPositions(List.of(position("ACME", null)))
                .openMechanisms(Map.of("ACME", "SPINOFF")).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.REDUNDANCY);
        assertThat(result(outcome, "REDUNDANCY").passed()).isFalse();
        assertThat(result(outcome, "REDUNDANCY").measured())
                .isEqualTo("position already open on ACME (mechanism SPINOFF)");
    }

    @Test
    void redundancy_contradictionFiresFirstButRedundancyStillRecordsFailure() {
        EntryContext ctx = ctx().openPositions(List.of(position("ACME", null)))
                .openMechanisms(Map.of("ACME", "MERGER_ARB")).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.CONTRADICTION);
        assertThat(result(outcome, "REDUNDANCY").passed()).isFalse();
        assertThat(result(outcome, "REDUNDANCY").measured())
                .isEqualTo("position already open on ACME (mechanism MERGER_ARB)");
    }

    @Test
    void redundancy_failsWhenTheOpenPositionsMechanismIsUnknown() {
        EntryContext ctx = ctx().openPositions(List.of(position("ACME", null))).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(result(outcome, "REDUNDANCY").passed()).isFalse();
        assertThat(result(outcome, "REDUNDANCY").measured())
                .isEqualTo("position already open on ACME (mechanism unknown)");
    }

    @Test
    void redundancy_matchesSymbolCaseInsensitively() {
        EntryContext ctx = ctx().openPositions(List.of(position("acme", null))).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(result(outcome, "REDUNDANCY").passed()).isFalse();
    }

    @Test
    void redundancy_passesWhenTheSameMechanismIsOpenOnAnotherSymbol() {
        EntryContext ctx = ctx().openPositions(List.of(position("OTHR", null)))
                .openMechanisms(Map.of("OTHR", "PEAD")).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(result(outcome, "REDUNDANCY").passed()).isTrue();
        assertThat(result(outcome, "REDUNDANCY").measured()).isEqualTo("no open position on symbol");
    }

    // ---- 11 LIQUIDITY ----

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

    // ---- 12 SIGNAL_EXPIRED ----

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

    @Test
    void firstFailureOrdering_signalExpiredBeatsTransientCap() {
        // Expired (age 6 > max 5) AND over the position cap (5 open, max 5) — expiry must win
        // so the signal goes terminal REJECTED instead of lingering PENDING on the transient cap.
        List<ExecutorPosition> open = List.of(position("A", "Tech"), position("B", "Tech"),
                position("C", "Tech"), position("D", "Tech"), position("E", "Tech"));
        EntryContext ctx = ctx().signalAgeTradingDays(6).openPositions(open).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.SIGNAL_EXPIRED);
        assertThat(result(outcome, "MAX_POSITIONS").passed()).isFalse(); // the cap really did trip
    }

    // ---- 13 CHASED_AWAY ----

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
    void chasedAway_sellFavorableMove_passes() {
        // referencePrice 50, chaseAtrMult 2 * atr 2 = 4 -> SELL threshold 46. price 55 (well above
        // the threshold, not yet collapsed away) -> passes.
        ExecutorSignal sig = new ExecutorSignal("sig-1", "s", "v1", "ACME", "SELL", 0.8, "PEAD",
                List.of("kill"), "20d", BigDecimal.valueOf(50), "PENDING", "2026-07-08T00:00:00Z");
        EntryContext ctx = ctx().price(BigDecimal.valueOf(55)).build();
        VetoService.Outcome outcome = vetoService.evaluate(sig, ctx, sizing(), cfg());

        assertThat(result(outcome, "CHASED_AWAY").passed()).isTrue();
    }

    @Test
    void chasedAway_sellCollapsedPrice_fails() {
        // SELL threshold 46 (50 - 2*2); price 44 < 46 -> the entry has already been chased away.
        ExecutorSignal sig = new ExecutorSignal("sig-1", "s", "v1", "ACME", "SELL", 0.8, "PEAD",
                List.of("kill"), "20d", BigDecimal.valueOf(50), "PENDING", "2026-07-08T00:00:00Z");
        EntryContext ctx = ctx().price(BigDecimal.valueOf(44)).build();
        VetoService.Outcome outcome = vetoService.evaluate(sig, ctx, sizing(), cfg());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.CHASED_AWAY);
    }

    @Test
    void evaluate_nullSignal_noException() {
        VetoService.Outcome outcome = vetoService.evaluate(null, ctx().build(), sizing(), cfg());

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.firstFailure()).isEqualTo(RejectReason.SCHEMA_INVALID);
        assertThat(result(outcome, "CHASED_AWAY").passed()).isTrue();
    }

    // ---- 14 BELOW_ANCHOR ----

    @Test
    void belowAnchor_pead_effectivePriceBelowReference_fails() {
        // PEAD (drift) long: reference 100.00, entry (market & order) 97.17, ATR any → adverse 2.83 > 0×ATR
        var signal = signalBuilder().mechanism("PEAD").direction("BUY")
                .referencePrice(BigDecimal.valueOf(100.00)).build();
        var ctx = ctx().price(BigDecimal.valueOf(97.17)).atr(BigDecimal.valueOf(4.59)).build();
        var out = vetoService.evaluate(signal, ctx, sizing(), cfg(), BigDecimal.valueOf(97.17));
        VetoResult r = out.results().stream().filter(v -> v.check().equals("BELOW_ANCHOR")).findFirst().orElseThrow();
        assertThat(r.passed()).isFalse();
        assertThat(out.firstFailure()).isEqualTo(RejectReason.BELOW_ANCHOR);
        assertThat(r.measured()).isEqualTo("adverse 2.83 > 0xATR 0.00");
    }

    @Test
    void belowAnchor_pead_atOrAboveReference_passes() {
        var s = signalBuilder().mechanism("PEAD").direction("BUY").referencePrice(BigDecimal.valueOf(100.00)).build();
        // == reference (band 0, compare >=) and above both pass
        assertThat(belowAnchorPasses(s, 100.00, 100.00, 4.59)).isTrue();
        assertThat(belowAnchorPasses(s, 100.79, 100.79, 4.59)).isTrue();
    }

    @Test
    void belowAnchor_index_belowReference_fails() {
        var s = signalBuilder().mechanism("INDEX_INCLUSION").direction("BUY").referencePrice(BigDecimal.valueOf(100)).build();
        assertThat(belowAnchorPasses(s, 99.00, 99.00, 2.0)).isFalse();
    }

    @Test
    void belowAnchor_effectiveOrderPriceBelowAnchor_fails() {
        // market ABOVE anchor but resting limit BELOW → min picks the limit → fail (M1)
        var s = signalBuilder().mechanism("PEAD").direction("BUY").referencePrice(BigDecimal.valueOf(100.00)).build();
        assertThat(belowAnchorPasses(s, /*market*/100.09, /*orderPrice*/96.79, 4.59)).isFalse();
    }

    @Test
    void belowAnchor_minSelectsMarket_whenLimitAboveButMarketBelow_fails() {
        var s = signalBuilder().mechanism("PEAD").direction("BUY").referencePrice(BigDecimal.valueOf(100.00)).build();
        assertThat(belowAnchorPasses(s, /*market*/96.29, /*orderPrice*/101.29, 4.59)).isFalse();
    }

    @Test
    void belowAnchor_value_ordinaryDipPasses_fallingKnifeFails() {
        var s = signalBuilder().mechanism("SPINOFF").direction("BUY").referencePrice(BigDecimal.valueOf(100)).build();
        assertThat(belowAnchorPasses(s, 98.0, 98.0, 1.0)).isTrue();     // −1×ATR within 3×ATR
        assertThat(belowAnchorPasses(s, 97.0, 97.0, 1.0)).isTrue();     // −3×ATR exactly (>= boundary)
        assertThat(belowAnchorPasses(s, 96.99, 96.99, 1.0)).isFalse();  // beyond 3×ATR
    }

    @Test
    void belowAnchor_valueLimitCase_appliesEffectivePrice() {
        var s = signalBuilder().mechanism("SPINOFF").direction("BUY").referencePrice(BigDecimal.valueOf(100)).build();
        assertThat(belowAnchorPasses(s, /*market*/101.0, /*orderPrice*/96.99, 1.0)).isFalse();
    }

    @Test
    void belowAnchor_insiderAndQualityBelowWithinBand_pass() {
        for (String m : new String[]{"INSIDER_CLUSTER", "QUALITY_52W_LOW", "MERGER_ARB", "WHATEVER"}) {
            var s = signalBuilder().mechanism(m).direction("BUY").referencePrice(BigDecimal.valueOf(100)).build();
            assertThat(belowAnchorPasses(s, 98.0, 98.0, 1.0)).as(m).isTrue(); // unknown → value band (fail-open)
        }
    }

    @Test
    void belowAnchor_lowercaseMechanism_treatedAsDrift() {
        var s = signalBuilder().mechanism("pead").direction("BUY").referencePrice(BigDecimal.valueOf(100)).build();
        assertThat(belowAnchorPasses(s, 97.1, 97.1, 1.0)).isFalse(); // 2.9×ATR below → drift(0) fails, not value(3)
    }

    @Test
    void belowAnchor_shortMirror() {
        var drift = signalBuilder().mechanism("PEAD").direction("SELL").referencePrice(BigDecimal.valueOf(100)).build();
        assertThat(belowAnchorPasses(drift, 100.5, 100.5, 1.0)).isFalse(); // above anchor → drift short fails
        assertThat(belowAnchorPasses(drift, 100.0, 100.0, 1.0)).isTrue();  // == anchor passes
        var value = signalBuilder().mechanism("SPINOFF").direction("SELL").referencePrice(BigDecimal.valueOf(100)).build();
        assertThat(belowAnchorPasses(value, 103.0, 103.0, 1.0)).isTrue();  // +3×ATR boundary
        assertThat(belowAnchorPasses(value, 103.01, 103.01, 1.0)).isFalse();
    }

    @Test
    void belowAnchor_nullReference_bothFail_firstFailureIsChasedAway() {
        var s = signalBuilder().mechanism("PEAD").direction("BUY").referencePrice(null).build();
        var out = vetoService.evaluate(s, ctx().price(BigDecimal.valueOf(50)).atr(BigDecimal.valueOf(2)).build(),
                sizing(), cfg(), BigDecimal.valueOf(50));
        assertThat(named(out, "CHASED_AWAY").passed()).isFalse();
        assertThat(named(out, "BELOW_ANCHOR").passed()).isFalse();
        assertThat(out.firstFailure()).isEqualTo(RejectReason.CHASED_AWAY);
    }

    @Test
    void belowAnchor_nullSignal_noNpe_passesNotEvaluated() {
        var out = vetoService.evaluate(null, ctx().price(BigDecimal.valueOf(50)).atr(BigDecimal.valueOf(2)).build(),
                sizing(), cfg(), BigDecimal.valueOf(50));
        assertThat(named(out, "BELOW_ANCHOR").passed()).isTrue();
        assertThat(named(out, "BELOW_ANCHOR").measured()).isEqualTo("not evaluated (schema invalid)");
    }

    @Test
    void belowAnchor_passSideRendersNegativeAdverse() {
        var s = signalBuilder().mechanism("PEAD").direction("BUY").referencePrice(BigDecimal.valueOf(100)).build();
        var out = vetoService.evaluate(s, ctx().price(BigDecimal.valueOf(103)).atr(BigDecimal.valueOf(1)).build(),
                sizing(), cfg(), BigDecimal.valueOf(103));
        assertThat(named(out, "BELOW_ANCHOR").measured()).isEqualTo("adverse -3.00 <= 0xATR 0.00");
    }

    @Test
    void belowAnchor_dataUnavailable_absentFromResults() {
        var s = signalBuilder().mechanism("PEAD").direction("BUY").referencePrice(BigDecimal.valueOf(100)).build();
        var out = vetoService.evaluate(s, ctx().missing(List.of("atr")).build(), sizing(), cfg(), null);
        assertThat(out.results().stream().anyMatch(v -> v.check().equals("BELOW_ANCHOR"))).isFalse();
        assertThat(out.firstFailure()).isEqualTo(RejectReason.DATA_UNAVAILABLE);
    }

    // ---- 15 PACE_LIMIT ----

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
    void allFourteenVetosAlwaysEvaluated_evenAfterFirstFailure() {
        EntryContext ctx = ctx().entriesThisWeek(3).build(); // fails PACE_LIMIT
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(outcome.results()).hasSize(18);
        assertThat(outcome.results().get(16).check()).isEqualTo("PACE_LIMIT");
        assertThat(outcome.results().get(17).check()).isEqualTo("CURRENCY_MISMATCH");
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

        List<String> expectedOrder = List.of("SCHEMA_INVALID", "LOW_CONFIDENCE", "SIGNAL_EXPIRED",
                "COOLDOWN", "MAX_POSITIONS", "MECHANISM_BUDGET", "BUDGET", "HEAT_LIMIT", "CONCENTRATION",
                "CORRELATED", "CONTRADICTION", "REDUNDANCY", "PATTERN_GATE", "LIQUIDITY", "CHASED_AWAY",
                "BELOW_ANCHOR", "PACE_LIMIT", "CURRENCY_MISMATCH");
        List<String> actualOrder = outcome.results().stream().map(VetoResult::check).toList();

        assertThat(actualOrder).isEqualTo(expectedOrder);
    }

    // ---- measured: every result carries a non-blank value+threshold string ----

    @Test
    void everyVetoResultCarriesMeasured() {
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx().build(), sizing(), cfg());

        assertThat(outcome.results()).isNotEmpty()
                .allSatisfy(r -> assertThat(r.measured()).isNotBlank());
        assertThat(result(outcome, "LOW_CONFIDENCE").measured()).isEqualTo("0.8 >= 0.6");
    }

    @Test
    void failedVetoMeasuredShowsValueAndThreshold() {
        ExecutorSignal sig = new ExecutorSignal("sig-1", "s", "v1", "ACME", "LONG", 0.4, "PEAD",
                List.of("kill"), "20d", BigDecimal.valueOf(50), "PENDING", "2026-07-08T00:00:00Z");
        VetoService.Outcome outcome = vetoService.evaluate(sig, ctx().build(), sizing(), cfg());

        assertThat(result(outcome, "LOW_CONFIDENCE").measured()).isEqualTo("0.4 < 0.6");
    }

    @Test
    void dataUnavailableMeasuredIsJoinedMissingFields() {
        EntryContext ctx = ctx().missing(List.of("price", "atr")).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(outcome.results().get(0).measured()).isEqualTo("price,atr");
    }

    @Test
    void schemaInvalidMeasuredNamesMissingField() {
        ExecutorSignal sig = new ExecutorSignal("sig-1", "s", "v1", null, "LONG", 0.8, "PEAD",
                List.of("kill"), "20d", BigDecimal.valueOf(50), "PENDING", "2026-07-08T00:00:00Z");
        VetoService.Outcome outcome = vetoService.evaluate(sig, ctx().build(), sizing(), cfg());

        assertThat(result(outcome, "SCHEMA_INVALID").measured()).isEqualTo("missing: symbol");
    }

    @Test
    void schemaValidMeasuredSummarizesFields() {
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx().build(), sizing(), cfg());

        assertThat(result(outcome, "SCHEMA_INVALID").measured())
                .isEqualTo("kill_criteria: 1, mechanism: PEAD, agent_version: v1");
    }

    @Test
    void cooldownMeasuredShowsExpiryOnFailure() {
        Cooldown cd = new Cooldown(1L, "ACME", "stopped out", "2026-08-01", null, "2026-07-01");
        EntryContext ctx = ctx().activeCooldowns(List.of(cd)).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(result(outcome, "COOLDOWN").measured()).isEqualTo("active until 2026-08-01");
    }

    @Test
    void maxPositionsMeasuredShowsCountVsLimit() {
        List<ExecutorPosition> open = List.of(position("A", "Tech"), position("B", "Tech"),
                position("C", "Tech"), position("D", "Tech"), position("E", "Tech"));
        EntryContext ctx = ctx().openPositions(open).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(result(outcome, "MAX_POSITIONS").measured()).isEqualTo("5 >= 5");
    }

    @Test
    void heatLimitMeasuredIsPercentageWithOneDecimal() {
        EntryContext ctx = ctx().openHeat(BigDecimal.valueOf(500)).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(result(outcome, "HEAT_LIMIT").measured()).isEqualTo("6.0% <= 6.0%");
    }

    @Test
    void concentrationMeasuredIncludesSector() {
        List<ExecutorPosition> open = List.of(position("A", "Tech"), position("B", "Tech"),
                position("C", "Tech"));
        EntryContext ctx = ctx().openPositions(open).candidateSector("Tech").build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(result(outcome, "CONCENTRATION").measured()).isEqualTo("3 >= 3 in sector Tech");
    }

    @Test
    void liquidityMeasuredShowsPriceAndThreshold() {
        EntryContext ctx = ctx().price(BigDecimal.valueOf(4)).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(result(outcome, "LIQUIDITY").measured()).isEqualTo("price 4.00 < 5.00, adv ok");
    }

    @Test
    void signalExpiredMeasuredShowsDaysVsMax() {
        EntryContext ctx = ctx().signalAgeTradingDays(6).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(result(outcome, "SIGNAL_EXPIRED").measured()).isEqualTo("6 > 5 days");
    }

    @Test
    void chasedAwayMeasuredShowsDriftVsAtrLimit_failure() {
        // ref 50, price 55 -> drift 5.00; chaseAtrMult 2 * atr 2 = 4.00 limit -> fail.
        // chaseAtrMult renders without trailing ".0" (2xATR, not 2.0xATR).
        EntryContext ctx = ctx().price(BigDecimal.valueOf(55)).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(result(outcome, "CHASED_AWAY").measured()).isEqualTo("drift 5.00 > 2xATR 4.00");
    }

    @Test
    void chasedAwayMeasuredShowsDriftVsAtrLimit_pass() {
        // ref 50, price 54 -> drift 4.00 exactly at the 2xATR limit 4.00 -> pass
        EntryContext ctx = ctx().price(BigDecimal.valueOf(54)).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(result(outcome, "CHASED_AWAY").measured()).isEqualTo("drift 4.00 <= 2xATR 4.00");
    }

    @Test
    void budgetMeasuredShowsCashTrancheAndExposureBudget() {
        // cash 100000, tranche 10000/10 = 1000, exposure 0 + 1000 = 1000, budget 10000
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx().build(), sizing(), cfg());

        assertThat(result(outcome, "BUDGET").measured())
                .isEqualTo("cash 100000.00 >= tranche 1000.00; exposure 1000.00 <= budget 10000.00");
    }

    @Test
    void paceLimitMeasuredShowsCountVsCap() {
        EntryContext ctx = ctx().entriesThisWeek(3).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(result(outcome, "PACE_LIMIT").measured()).isEqualTo("3 >= 3 this week");
    }

    @Test
    void contradictionMeasuredNamesContradictingSignal() {
        ExecutorSignal candidate = new ExecutorSignal("sig-1", "s", "v1", "ACME", "LONG", 0.8,
                "MERGER_ARB", List.of("kill"), "20d", BigDecimal.valueOf(50), "PENDING",
                "2026-07-08T00:00:00Z");
        ExecutorSignal contradicting = pending("sig-2", "ACME", "PEAD");
        EntryContext ctx = ctx().pendingSignals(List.of(contradicting)).build();
        VetoService.Outcome outcome = vetoService.evaluate(candidate, ctx, sizing(), cfg());

        assertThat(result(outcome, "CONTRADICTION").measured())
                .isEqualTo("conflicts with pending signal sig-2");
    }

    @Test
    void redundancyMeasuredNamesMechanismAndSymbol() {
        EntryContext ctx = ctx().openPositions(List.of(position("ACME", null)))
                .openMechanisms(Map.of("ACME", "PEAD")).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), ctx, sizing(), cfg());

        assertThat(result(outcome, "REDUNDANCY").measured())
                .isEqualTo("mechanism PEAD already open on ACME");
    }

    @Test
    void correlatedMeasuredNamesMatchingSymbol() {
        ExecutorSignal s = signal(); // default mechanism "PEAD"
        EntryContext ctx = ctx()
                .openPositions(List.of(position("XYZ", "Technology")))
                .openMechanisms(Map.of("XYZ", s.mechanism()))
                .candidateSector("Technology")
                .build();
        VetoService.Outcome outcome = vetoService.evaluate(s, ctx, sizing(), cfg());

        assertThat(result(outcome, "CORRELATED").measured()).isEqualTo("matches XYZ in sector Technology");
    }

    private VetoResult result(VetoService.Outcome outcome, String check) {
        return outcome.results().stream()
                .filter(r -> r.check().equals(check))
                .findFirst().orElseThrow();
    }

    // ---- PATTERN_GATE (T3.3 D4) ----

    private EnforcedGate mechanismGate(String id, String mechanism) {
        return new EnforcedGate(id, "test-gate", "{\"conditions\":[{\"field\":\"mechanism\","
                + "\"op\":\"eq\",\"value\":\"" + mechanism + "\"}]}");
    }

    @Test
    void patternGate_firesWithIdFirstDetail() {
        var out = vetoService.evaluate(signal(), ctx().build(), sizing(), cfg(),
                BigDecimal.valueOf(50), List.of(mechanismGate("11111111-aaaa-bbbb-cccc-000000000001", "PEAD")));

        assertThat(out.passed()).isFalse();
        assertThat(out.firstFailure()).isEqualTo(RejectReason.PATTERN_GATE);
        VetoResult r = named(out, "PATTERN_GATE");
        assertThat(r.passed()).isFalse();
        assertThat(r.measured()).startsWith("pattern_gate:11111111-aaaa-bbbb-cccc-000000000001");
        assertThat(r.measured()).contains("test-gate");
    }

    @Test
    void patternGate_noMatchPasses() {
        var out = vetoService.evaluate(signal(), ctx().build(), sizing(), cfg(),
                BigDecimal.valueOf(50), List.of(mechanismGate("id-1", "MERGER_ARB")));

        assertThat(out.passed()).isTrue();
        assertThat(named(out, "PATTERN_GATE").passed()).isTrue();
        assertThat(named(out, "PATTERN_GATE").measured()).isEqualTo("no enforced gate matched");
    }

    @Test
    void patternGate_emptyGatesListIsRegressionIdenticalToToday() {
        var withEmpty = vetoService.evaluate(signal(), ctx().build(), sizing(), cfg(),
                BigDecimal.valueOf(50), List.of());
        var legacy = vetoService.evaluate(signal(), ctx().build(), sizing(), cfg());

        assertThat(withEmpty.passed()).isTrue();
        assertThat(legacy.passed()).isTrue();
        assertThat(withEmpty.firstFailure()).isNull();
        // Same checks in the same order, PATTERN_GATE traced as PASS in both.
        assertThat(withEmpty.results()).isEqualTo(legacy.results());
        assertThat(named(legacy, "PATTERN_GATE").passed()).isTrue();
    }

    @Test
    void patternGate_catalogPosition_redundancyBeatsIt() {
        // REDUNDANCY (#11) fires: same mechanism already open on the same symbol. The open
        // position's sector deliberately differs from the candidate sector ("Tech"), or
        // CORRELATED (#9, sector+mechanism) would fire first and mask the ordering assert.
        var ctx = ctx().openPositions(List.of(position("ACME", "Energy")))
                .openMechanisms(Map.of("ACME", "PEAD")).build();
        var out = vetoService.evaluate(signal(), ctx, sizing(), cfg(),
                BigDecimal.valueOf(50), List.of(mechanismGate("id-1", "PEAD")));

        assertThat(out.firstFailure()).isEqualTo(RejectReason.REDUNDANCY);
        assertThat(named(out, "PATTERN_GATE").passed()).isFalse();
    }

    @Test
    void patternGate_catalogPosition_beatsLiquidity() {
        // LIQUIDITY would fire (price 4 < minPrice 5), but the gate is checked first.
        var ctx = ctx().price(BigDecimal.valueOf(4)).build();
        var out = vetoService.evaluate(signal(), ctx, sizing(), cfg(),
                BigDecimal.valueOf(4), List.of(mechanismGate("id-1", "PEAD")));

        assertThat(out.firstFailure()).isEqualTo(RejectReason.PATTERN_GATE);
        assertThat(named(out, "LIQUIDITY").passed()).isFalse();
    }

    @Test
    void patternGate_firstMatchingGateInRepositoryOrderWinsTheDetail() {
        var out = vetoService.evaluate(signal(), ctx().build(), sizing(), cfg(),
                BigDecimal.valueOf(50),
                List.of(mechanismGate("id-first", "PEAD"), mechanismGate("id-second", "PEAD")));

        assertThat(named(out, "PATTERN_GATE").measured()).startsWith("pattern_gate:id-first");
    }

    @Test
    void patternGate_malformedStoredGateSkippedWithoutException() {
        var broken = new EnforcedGate("id-broken", "broken", "{not json");
        var out = vetoService.evaluate(signal(), ctx().build(), sizing(), cfg(),
                BigDecimal.valueOf(50), List.of(broken));

        assertThat(out.passed()).isTrue();
        assertThat(named(out, "PATTERN_GATE").passed()).isTrue();
    }

    @Test
    void patternGate_unevaluableGateFailsOpen() {
        // Sector condition but ctx has no sector -> not evaluable -> no fire.
        var sectorGate = new EnforcedGate("id-sec", "sector-gate",
                "{\"conditions\":[{\"field\":\"sector\",\"op\":\"eq\",\"value\":\"Tech\"}]}");
        var ctx = ctx().candidateSector(null).build();
        var out = vetoService.evaluate(signal(), ctx, sizing(), cfg(),
                BigDecimal.valueOf(50), List.of(sectorGate));

        assertThat(named(out, "PATTERN_GATE").passed()).isTrue();
    }

    @Test
    void patternGate_isTransient() {
        assertThat(RejectReason.PATTERN_GATE.isTransient()).isTrue();
    }

    // ---- HEAT_LIMIT consumes logical risk, never broker risk (regression) ----

    /** Test 30. HEAT_LIMIT consumes position_risk — the loss the CLOSE-BASED rule intends — and
     *  never position_risk_broker, the wider loss the resting leg permits. That is a deliberate
     *  reversal of an earlier review decision: on broker risk, five positions would occupy ~40 % of
     *  the heat limit on day one, making max-positions and heat-pct mutually unreachable through a
     *  TRANSIENT reason that leaves signals silently PENDING.
     *  Mutation: feed the veto a risk figure computed from the broker stop (here 1.4x larger,
     *  which tips this fixture over the limit). */
    @Test
    void heatLimitStillConsumesLogicalRisk() {
        // heat limit = totalBudget 10000 * heatPct 0.06 = 600. Open heat 540 already booked.
        // Logical new risk 50 -> 590 <= 600, PASS.
        // Broker risk for the same position would be 50 * (7/5) = 70 -> 610 > 600, FAIL.
        Sizing logical = new Sizing(new BigDecimal("10"), new BigDecimal("5"),
                new BigDecimal("50.0000"), new BigDecimal("93.5"), new BigDecimal("95"), true,
                "entry - 2.5 x ATR22", new BigDecimal("10"), new BigDecimal("20"), "NOTIONAL", null);

        EntryContext heatCtx = ctx().openHeat(BigDecimal.valueOf(540)).build();
        VetoService.Outcome outcome = vetoService.evaluate(signal(), heatCtx, logical, cfg());

        VetoResult heat = named(outcome, "HEAT_LIMIT");
        assertThat(heat.passed()).as("590 <= 600 on LOGICAL risk").isTrue();
        assertThat(outcome.firstFailure()).isNotEqualTo(RejectReason.HEAT_LIMIT);
    }

    // ---- MECHANISM_BUDGET (5b) ----

    @Test
    void mechanismBudgetFailsWhenHeldPlusTrancheExceedsCap() {
        var ctx = ctx().openExposureByMechanism(Map.of("MERGER_ARB", new BigDecimal("3864.77"))).build();
        var out = vetoService.evaluate(signalBuilder().mechanism("MERGER_ARB").build(), ctx, sizing(),
                cfgWithBudget(MERGER_SPEC, 8));
        assertThat(named(out, "MECHANISM_BUDGET").passed()).isFalse();
        assertThat(named(out, "MECHANISM_BUDGET").measured())
                .isEqualTo("MERGER_ARB 3864.77 + 1000.00 > entry cap 2000.00 (20% of 10000.00)");
        assertThat(out.firstFailure()).isEqualTo(RejectReason.MECHANISM_BUDGET);
    }

    @Test
    void mechanismBudgetPassesAtExactCap() {
        var ctx = ctx().openExposureByMechanism(Map.of("MERGER_ARB", new BigDecimal("1000.00"))).build();
        var out = vetoService.evaluate(signalBuilder().mechanism("MERGER_ARB").build(), ctx, sizing(),
                cfgWithBudget(MERGER_SPEC, 8));
        assertThat(named(out, "MECHANISM_BUDGET").passed()).isTrue();
        assertThat(named(out, "MECHANISM_BUDGET").measured())
                .isEqualTo("MERGER_ARB 1000.00 + 1000.00 <= entry cap 2000.00 (20% of 10000.00)");
    }

    @Test
    void mechanismBudgetIgnoresOtherMechanisms() {
        var ctx = ctx().openExposureByMechanism(Map.of("PEAD", new BigDecimal("9000.00"))).build();
        var out = vetoService.evaluate(signalBuilder().mechanism("MERGER_ARB").build(), ctx, sizing(),
                cfgWithBudget(MERGER_SPEC, 8));
        assertThat(named(out, "MECHANISM_BUDGET").measured())
                .isEqualTo("MERGER_ARB 0.00 + 1000.00 <= entry cap 2000.00 (20% of 10000.00)");
    }

    @Test
    void mechanismBudgetSkipsUnlistedMechanism() {
        var out = vetoService.evaluate(signal(), ctx().build(), sizing(), cfgWithBudget(MERGER_SPEC, 8));
        assertThat(named(out, "MECHANISM_BUDGET").passed()).isTrue();
        assertThat(named(out, "MECHANISM_BUDGET").measured()).isEqualTo("no cap for PEAD");
    }

    @Test
    void mechanismBudgetReportsUnresolved() {
        var ctx = ctx().openExposureByMechanism(Map.of(
                "MERGER_ARB", new BigDecimal("500.00"), "UNRESOLVED", new BigDecimal("928.38"))).build();
        var out = vetoService.evaluate(signalBuilder().mechanism("MERGER_ARB").build(), ctx, sizing(),
                cfgWithBudget(MERGER_SPEC, 8));
        assertThat(named(out, "MECHANISM_BUDGET").passed()).isTrue();
        assertThat(named(out, "MECHANISM_BUDGET").measured())
                .isEqualTo("MERGER_ARB 500.00 + 1000.00 <= entry cap 2000.00 (20% of 10000.00); unresolved: 928.38");
    }

    @Test
    void mechanismBudgetIsCaseInsensitiveOnTheMap() {
        var ctx = ctx().openExposureByMechanism(Map.of("MERGER_ARB", new BigDecimal("3000.00"))).build();
        var out = vetoService.evaluate(signalBuilder().mechanism("merger_arb").build(), ctx, sizing(),
                cfgWithBudget("merger_arb:0.2", 8));
        assertThat(named(out, "MECHANISM_BUDGET").passed()).isFalse();
    }

    @Test
    void mechanismBudgetPrecedenceAfterMaxPositions() {
        List<ExecutorPosition> eight = java.util.Collections.nCopies(8, position("X", "Tech"));
        var ctx = ctx().openPositions(eight)
                .openExposureByMechanism(Map.of("MERGER_ARB", new BigDecimal("3864.77"))).build();
        var out = vetoService.evaluate(signalBuilder().mechanism("MERGER_ARB").build(), ctx, sizing(),
                cfgWithBudget(MERGER_SPEC, 8));
        assertThat(out.firstFailure()).isEqualTo(RejectReason.MAX_POSITIONS);
        assertThat(named(out, "MECHANISM_BUDGET").passed()).isFalse();
    }

    @Test
    void mechanismBudgetPrecedesBudget() {
        var ctx = ctx().account(new AccountSnapshot(BigDecimal.valueOf(100), BigDecimal.valueOf(100), "USD"))
                .openExposureByMechanism(Map.of("MERGER_ARB", new BigDecimal("3864.77"))).build();
        var out = vetoService.evaluate(signalBuilder().mechanism("MERGER_ARB").build(), ctx, sizing(),
                cfgWithBudget(MERGER_SPEC, 8));
        assertThat(out.firstFailure()).isEqualTo(RejectReason.MECHANISM_BUDGET);
        assertThat(named(out, "BUDGET").passed()).isFalse();
    }

    @Test
    void mechanismBudgetTracesPassWhenSchemaInvalid() {
        var blank = vetoService.evaluate(signalBuilder().mechanism("").build(), ctx().build(), sizing(),
                cfgWithBudget(MERGER_SPEC, 8));
        assertThat(named(blank, "MECHANISM_BUDGET").passed()).isTrue();
        assertThat(named(blank, "MECHANISM_BUDGET").measured()).isEqualTo("not evaluated (schema invalid)");
        assertThat(blank.firstFailure()).isEqualTo(RejectReason.SCHEMA_INVALID);

        var nul = vetoService.evaluate(null, ctx().build(), sizing(), cfgWithBudget(MERGER_SPEC, 8));
        assertThat(named(nul, "MECHANISM_BUDGET").measured()).isEqualTo("not evaluated (schema invalid)");
    }

    @Test
    void mechanismBudgetNonRoundSharePercentFormat() {
        var ctx = ctx().openExposureByMechanism(Map.of("MERGER_ARB", new BigDecimal("0.00"))).build();
        var out = vetoService.evaluate(signalBuilder().mechanism("MERGER_ARB").build(), ctx, sizing(),
                cfgWithBudget("MERGER_ARB:0.155", 8));
        assertThat(named(out, "MECHANISM_BUDGET").measured())
                .isEqualTo("MERGER_ARB 0.00 + 1000.00 <= entry cap 1550.00 (15.5% of 10000.00)");
    }

    @Test
    void vetoCatalogOrderAfterHoist() {
        var out = vetoService.evaluate(signal(), ctx().build(), sizing(), cfg());
        assertThat(out.results()).extracting(VetoResult::check).containsExactly(
                "SCHEMA_INVALID", "LOW_CONFIDENCE", "SIGNAL_EXPIRED", "COOLDOWN", "MAX_POSITIONS",
                "MECHANISM_BUDGET", "BUDGET", "HEAT_LIMIT", "CONCENTRATION", "CORRELATED",
                "CONTRADICTION", "REDUNDANCY", "PATTERN_GATE", "LIQUIDITY", "CHASED_AWAY",
                "BELOW_ANCHOR", "PACE_LIMIT", "CURRENCY_MISMATCH");
        assertThat(named(out, "BUDGET").measured()).contains("cash ").contains("; exposure ");
        assertThat(out.snapshot().budgetFree()).isEqualByComparingTo("9000.00");
    }

    @Test
    void lowerFloorLetsAFormerlyTerminalSignalThrough() {
        // prod MFP 2026-09-02: LOW_CONFIDENCE:FAIL (0.62 < 0.65) under exec-v0.5
        ExecutorSignal s = new ExecutorSignal("sig-1", "strigoi-test", "v1", "ACME", "LONG", 0.62, "PEAD",
                List.of("Close below 90.00"), "20d", BigDecimal.valueOf(50), "PENDING", "2026-07-08T00:00:00Z");
        VetoConfig old = new VetoConfig(0.65, 5, BigDecimal.valueOf(10000), 0.06, 3,
                BigDecimal.valueOf(5), 20, 5, 2.0, 3, 10, 0.0, 3.0, "USD", MechanismBudget.none());
        VetoConfig neu = new VetoConfig(0.40, 5, BigDecimal.valueOf(10000), 0.06, 3,
                BigDecimal.valueOf(5), 20, 5, 2.0, 3, 10, 0.0, 3.0, "USD", MechanismBudget.none());
        assertThat(vetoService.evaluate(s, ctx().build(), sizing(), old).firstFailure()).isEqualTo(RejectReason.LOW_CONFIDENCE);
        assertThat(vetoService.evaluate(s, ctx().build(), sizing(), neu).passed()).isTrue();
    }

    // ---- exit profile CONVICTION (spec 2026-10-03 §5.3) ----

    private ExecutorSignal conviction() {
        return signalBuilder().mechanism("TECH_CONVICTION").build();
    }

    /** qty 33 at 100 -> a 3 300 profile notional (fx 1). */
    private Sizing convictionSizing() {
        return ExecutorWebhookController.convictionSizing("BUY", new BigDecimal("100"),
                new BigDecimal("65"), new BigDecimal("3300"), BigDecimal.ONE);
    }

    private VetoConfig techBudgetCfg() {
        return new VetoConfig(0.6, 100, BigDecimal.valueOf(100000), 0.15, 100,
                BigDecimal.valueOf(5), 20, 5, 2.0, 10, 25, 0.0, 3.0, "USD",
                new MechanismBudget("TECH_CONVICTION:0.44"));
    }

    /** P1 #8: a CONVICTION signal skips CORRELATED with {skipped:"profile"}; an otherwise
     *  identical STANDARD signal still fails it. */
    @Test
    void convictionSkipsCorrelatedWhileStandardStillFails() {
        var book = ctx().candidateSector("Tech").openPositions(List.of(position("SYNB", "Tech")))
                .openMechanisms(Map.of("SYNB", "TECH_CONVICTION"));

        var conv = vetoService.evaluate(conviction(), book.build(), convictionSizing(), cfg(),
                new BigDecimal("100"));
        assertThat(named(conv, "CORRELATED").passed()).isTrue();
        assertThat(named(conv, "CORRELATED").skipped()).isEqualTo("profile");

        var std = vetoService.evaluate(signalBuilder().mechanism("tech_conviction_standard").build(),
                book.openMechanisms(Map.of("SYNB", "tech_conviction_standard")).build(), sizing(),
                cfg());
        assertThat(named(std, "CORRELATED").passed()).isFalse();
        assertThat(named(std, "CORRELATED").skipped()).isNull();
    }

    /** P1 #8: CONCENTRATION — CONVICTION signals skip it; STANDARD signals count STANDARD
     *  positions only (three CONVICTION names in the sector do not block a STANDARD entry). */
    @Test
    void concentrationIsSplitByProfile() {
        List<ExecutorPosition> threeConviction = List.of(
                ExecutorPositionFixtures.conviction(position("SYNA", "Tech")),
                ExecutorPositionFixtures.conviction(position("SYNB", "Tech")),
                ExecutorPositionFixtures.conviction(position("SYNC", "Tech")));

        var std = vetoService.evaluate(signal(), ctx().candidateSector("Tech")
                .openPositions(threeConviction).build(), sizing(), cfg());
        assertThat(named(std, "CONCENTRATION").passed()).isTrue();

        var stdBlocked = vetoService.evaluate(signal(), ctx().candidateSector("Tech")
                .openPositions(List.of(position("SYND", "Tech"), position("SYNE", "Tech"),
                        position("SYNF", "Tech"))).build(), sizing(), cfg());
        assertThat(named(stdBlocked, "CONCENTRATION").passed()).isFalse();

        var conv = vetoService.evaluate(conviction(), ctx().candidateSector("Tech")
                .openPositions(List.of(position("SYND", "Tech"), position("SYNE", "Tech"),
                        position("SYNF", "Tech"))).build(), convictionSizing(), cfg(),
                new BigDecimal("100"));
        assertThat(named(conv, "CONCENTRATION").passed()).isTrue();
        assertThat(named(conv, "CONCENTRATION").skipped()).isEqualTo("profile");
    }

    /** P1 #8: HEAT_LIMIT is skipped for CONVICTION signals even when the book is at the limit. */
    @Test
    void convictionSkipsHeat() {
        var full = ctx().openHeat(BigDecimal.valueOf(600)); // cfg heat 0.06 x 10000 = 600
        assertThat(named(vetoService.evaluate(signal(), full.build(), sizing(), cfg()),
                "HEAT_LIMIT").passed()).isFalse();

        var conv = vetoService.evaluate(conviction(), full.build(), convictionSizing(), cfg(),
                new BigDecimal("100"));
        assertThat(named(conv, "HEAT_LIMIT").passed()).isTrue();
        assertThat(named(conv, "HEAT_LIMIT").skipped()).isEqualTo("profile");
        assertThat(conv.results()).hasSize(18);
    }

    /** P1 #8 (R1 M6): BUDGET and MECHANISM_BUDGET charge the actual profile notional (3 300),
     *  not total-budget / tranche-count (4 000). 40 100 held + 3 300 = 43 400 <= 44 000 passes;
     *  a 4 000 charge (44 100) would fail — the discriminator. */
    @Test
    void convictionChargesTheProfileNotional() {
        var out = vetoService.evaluate(conviction(), ctx()
                        .openExposure(BigDecimal.valueOf(40100))
                        .openExposureByMechanism(Map.of("TECH_CONVICTION", BigDecimal.valueOf(40100)))
                        .build(),
                convictionSizing(), techBudgetCfg(), new BigDecimal("100"));

        assertThat(named(out, "MECHANISM_BUDGET").passed()).isTrue();
        assertThat(named(out, "MECHANISM_BUDGET").measured()).contains("3300.00");
        assertThat(named(out, "BUDGET").passed()).isTrue();
    }

    /** P1 #8 (R2 Minor 5): an illustrative fixture, not the shipped basket-size/position-pct/
     *  MECHANISM_BUDGET defaults (those are 10 / 3 % / 0.33 as of 2026-10-05) — twelve names at a
     *  round 3 300 notional fit a round MECHANISM_BUDGET of 0.44 including a 2 % adverse FX drift
     *  of the eleven held names valued at today's rate (11 x 3 300 x 1.02 = 37 026); this only
     *  exercises the charge/cap arithmetic in {@code techBudgetCfg()} above, which is itself an
     *  independent fixture, not bound to the real Spring-configured defaults. */
    @Test
    void twelveNamesFitTheTechBudgetWithTwoPercentFxDrift() {
        BigDecimal held = new BigDecimal("37026.00");
        var out = vetoService.evaluate(conviction(), ctx().openExposure(held)
                        .openExposureByMechanism(Map.of("TECH_CONVICTION", held)).build(),
                convictionSizing(), techBudgetCfg(), new BigDecimal("100"));
        assertThat(named(out, "MECHANISM_BUDGET").passed()).isTrue();
    }

    /** STANDARD capital arithmetic is unchanged: it still charges the tranche. */
    @Test
    void standardStillChargesTheTranche() {
        var out = vetoService.evaluate(signal(), ctx().openExposure(BigDecimal.valueOf(9500)).build(),
                sizing(), cfg()); // tranche 10000/10 = 1000 -> 10500 > 10000
        assertThat(named(out, "BUDGET").passed()).isFalse();
    }

    /** BELOW_ANCHOR uses the VALUE band (value-anchor-atr-mult) for TECH_CONVICTION. */
    @Test
    void convictionUsesTheValueAnchorBand() {
        // reference 50, ATR 2: value band 3 x 2 = 6 -> an effective entry of 45 is inside
        assertThat(belowAnchorPasses(conviction(), 45, 45, 2)).isTrue();
        assertThat(belowAnchorPasses(conviction(), 43, 43, 2)).isFalse();
    }

    @Test
    void skippedChecksSerialiseWithTheMarker() {
        assertThat(VetoResult.skipped("HEAT_LIMIT", "x"))
                .isEqualTo(new VetoResult("HEAT_LIMIT", true, "x", "profile"));
        assertThat(new VetoResult("BUDGET", false, "y").skipped()).isNull();
    }

    // ---- exit profile MOMENTUM (spec 2026-10-04 §3) ----

    private ExecutorSignal momentum() {
        return signalBuilder().mechanism("MOMENTUM_12_1").build();
    }

    /** qty 25 at 100 -> a 2 500 profile notional (fx 1). */
    private Sizing momentumSizing() {
        return ExecutorWebhookController.profileSizing(ExitProfile.MOMENTUM, "BUY",
                new BigDecimal("100"), new BigDecimal("65"), new BigDecimal("2500"), BigDecimal.ONE);
    }

    /** CORRELATED, CONCENTRATION and HEAT_LIMIT are skipped for MOMENTUM like CONVICTION, and the
     *  skip text names the profile. */
    @Test
    void momentumSkipsCorrelatedConcentrationAndHeatWithAProfileNamedLabel() {
        var book = ctx().candidateSector("Tech")
                .openPositions(List.of(position("SYNB", "Tech"), position("SYNC", "Tech"),
                        position("SYND", "Tech"), position("SYNE", "Tech"), position("SYNF", "Tech")))
                .openMechanisms(Map.of("SYNB", "MOMENTUM_12_1"))
                .openHeat(BigDecimal.valueOf(10000));

        var out = vetoService.evaluate(momentum(), book.build(), momentumSizing(), cfg(),
                new BigDecimal("100"));

        assertThat(named(out, "CORRELATED").skipped()).isEqualTo("profile");
        assertThat(named(out, "CORRELATED").measured()).isEqualTo("skipped (exit profile MOMENTUM)");
        assertThat(named(out, "CONCENTRATION").skipped()).isEqualTo("profile");
        assertThat(named(out, "CONCENTRATION").measured()).startsWith("skipped (exit profile MOMENTUM); 5 STANDARD");
        assertThat(named(out, "HEAT_LIMIT").skipped()).isEqualTo("profile");
        assertThat(named(out, "HEAT_LIMIT").measured()).startsWith("skipped (exit profile MOMENTUM)");
        assertThat(out.results()).hasSize(18);
    }

    /** MOMENTUM positions do not count toward a STANDARD signal's CONCENTRATION. */
    @Test
    void momentumPositionsDoNotCountForStandardConcentration() {
        List<ExecutorPosition> momentumBook = List.of(
                ExecutorPositionFixtures.withProfileFields(position("SYNA", "Tech"), ExitProfile.MOMENTUM, null, null, null, false),
                ExecutorPositionFixtures.withProfileFields(position("SYNB", "Tech"), ExitProfile.MOMENTUM, null, null, null, false),
                ExecutorPositionFixtures.withProfileFields(position("SYNC", "Tech"), ExitProfile.MOMENTUM, null, null, null, false));

        var std = vetoService.evaluate(signal(), ctx().candidateSector("Tech").openPositions(momentumBook).build(),
                sizing(), cfg());

        assertThat(named(std, "CONCENTRATION").passed()).isTrue();
        assertThat(named(std, "CONCENTRATION").measured()).startsWith("0 < 3");
    }

    /** CONVICTION skip texts stay byte-identical. */
    @Test
    void convictionSkipTextsAreUnchanged() {
        var out = vetoService.evaluate(conviction(), ctx().build(), convictionSizing(), cfg(),
                new BigDecimal("100"));
        assertThat(named(out, "CORRELATED").measured()).isEqualTo("skipped (exit profile CONVICTION)");
        assertThat(named(out, "HEAT_LIMIT").measured()).startsWith("skipped (exit profile CONVICTION); STANDARD heat ");
    }

    /** Spec §3 (R2 Minor 7): LOW_CONFIDENCE, CHASED_AWAY and BELOW_ANCHOR are skipped for
     *  MOMENTUM — the same contexts reject a STANDARD signal. */
    @Test
    void momentumSkipsLowConfidenceChasedAwayAndBelowAnchor() {
        ExecutorSignal lowConfidence = signalBuilder().mechanism("MOMENTUM_12_1").confidence(0.3).build();
        var chased = ctx().price(BigDecimal.valueOf(60)).build();   // ref 50 + 2 x ATR 2 = 54 < 60
        var below = ctx().price(BigDecimal.valueOf(40)).build();    // ref 50, drift band 0 -> 40 < 50

        assertThat(vetoService.evaluate(signalBuilder().build(), chased, sizing(), cfg())
                .firstFailure()).isEqualTo(RejectReason.CHASED_AWAY);
        assertThat(vetoService.evaluate(signalBuilder().build(), below, sizing(), cfg())
                .firstFailure()).isEqualTo(RejectReason.BELOW_ANCHOR);

        var mChased = vetoService.evaluate(lowConfidence, chased, momentumSizing(), cfg());
        var mBelow = vetoService.evaluate(lowConfidence, below, momentumSizing(), cfg());

        assertThat(mChased.passed()).isTrue();
        assertThat(mBelow.passed()).isTrue();
        assertThat(named(mChased, "LOW_CONFIDENCE").skipped()).isEqualTo("profile");
        assertThat(named(mChased, "LOW_CONFIDENCE").measured())
                .isEqualTo("skipped (exit profile MOMENTUM); rule-based confidence 0.3");
        assertThat(named(mChased, "CHASED_AWAY").skipped()).isEqualTo("profile");
        assertThat(named(mBelow, "BELOW_ANCHOR").skipped()).isEqualTo("profile");
        assertThat(mChased.results()).hasSize(18);
    }

    /** Spec §3 (R2 M2): PACE_LIMIT is skipped for both wide-stop profiles, STANDARD still fails. */
    @Test
    void paceLimitIsSkippedForWideStopProfiles() {
        var fullWeek = ctx().entriesThisWeek(3).build();   // cfg() pace-per-week 3

        assertThat(vetoService.evaluate(signalBuilder().build(), fullWeek, sizing(), cfg())
                .firstFailure()).isEqualTo(RejectReason.PACE_LIMIT);
        var conv = vetoService.evaluate(conviction(), fullWeek, convictionSizing(), cfg(),
                new BigDecimal("100"));
        var mom = vetoService.evaluate(momentum(), fullWeek, momentumSizing(), cfg(),
                new BigDecimal("100"));

        assertThat(named(conv, "PACE_LIMIT").skipped()).isEqualTo("profile");
        assertThat(named(conv, "PACE_LIMIT").measured())
                .isEqualTo("skipped (exit profile CONVICTION); 3 STANDARD entries this week");
        assertThat(named(mom, "PACE_LIMIT").skipped()).isEqualTo("profile");
        // CONVICTION keeps CHASED_AWAY / BELOW_ANCHOR / LOW_CONFIDENCE as real checks
        assertThat(named(conv, "LOW_CONFIDENCE").skipped()).isNull();
        assertThat(named(conv, "CHASED_AWAY").skipped()).isNull();
        assertThat(named(conv, "BELOW_ANCHOR").skipped()).isNull();
    }

    /** Spec §3 (R2 M1): for a MOMENTUM signal a committed rebalance exit does not count toward
     *  MAX_POSITIONS; a STANDARD signal still counts it. */
    @Test
    void maxPositionsExcludesCommittedRebalanceExitsForMomentumOnly() {
        List<ExecutorPosition> book = List.of(
                ExecutorPositionFixtures.withRebalanceExitAt(ExecutorPositionFixtures.momentum(
                        position("SYNA", "Tech")), "2026-10-30 22:40:00+00"),
                ExecutorPositionFixtures.momentum(position("SYNB", "Energy")));
        var cfg2 = cfgWithBudget(MERGER_SPEC, 2);

        var std = vetoService.evaluate(signalBuilder().build(), ctx().openPositions(book).build(),
                sizing(), cfg2);
        var mom = vetoService.evaluate(momentum(), ctx().openPositions(book).build(),
                momentumSizing(), cfg2, new BigDecimal("100"));

        assertThat(std.firstFailure()).isEqualTo(RejectReason.MAX_POSITIONS);
        assertThat(named(mom, "MAX_POSITIONS").passed()).isTrue();
        assertThat(named(mom, "MAX_POSITIONS").measured())
                .isEqualTo("1 < 2 (excl. 1 committed rebalance exit(s))");
    }
}
