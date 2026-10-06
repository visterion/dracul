package de.visterion.dracul.executor;

import jakarta.annotation.PostConstruct;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Ensures the configured active rule version exists in {@code rule_versions}, seeding it on first boot.
 *
 * <p>{@code @DependsOn("flyway")} is required: this bean's {@code @PostConstruct} queries the
 * database eagerly during context startup, and {@code FlywayConfig}'s hand-rolled {@code Flyway}
 * bean (not Spring Boot's auto-configured one) carries no automatic depends-on wiring against
 * {@code JdbcClient} consumers — without this, migrations may not have run yet.
 */
@Component
@DependsOn("flyway")
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class RuleVersionProvider {

    private final String active;
    private final RuleVersionRepository repo;
    private final ObjectMapper mapper;
    private final BigDecimal brokerStopBufferAtr;
    private final BigDecimal maxBrokerStopPct;
    private final int atrShortPeriod;
    private final double riskPct;
    private final double minConfidence;
    private final int maxPositions;
    private final BigDecimal totalBudget;
    private final int trancheCount;
    private final double heatPct;
    private final int pacePerWeek;
    private final int maxPerSector;
    private final int cooldownDays;
    private final MechanismBudget mechanismBudget;
    private final ConvictionProfile convictionProfile;
    private final SavingsPlanSettings savingsPlanSettings;

    public RuleVersionProvider(
            @Value("${dracul.executor.rule-version:exec-v1.3}") String active,
            RuleVersionRepository repo,
            ObjectMapper mapper,
            @Value("${dracul.executor.broker-stop-buffer-atr:1.0}") BigDecimal brokerStopBufferAtr,
            @Value("${dracul.executor.max-broker-stop-pct:0.20}") BigDecimal maxBrokerStopPct,
            @Value("${dracul.executor.atr-short-period:5}") int atrShortPeriod,
            @Value("${dracul.executor.risk-pct:0.005}") double riskPct,
            @Value("${dracul.executor.min-confidence:0.40}") double minConfidence,
            @Value("${dracul.executor.max-positions:35}") int maxPositions,
            @Value("${dracul.executor.total-budget:100000}") BigDecimal totalBudget,
            @Value("${dracul.executor.tranche-count:25}") int trancheCount,
            @Value("${dracul.executor.heat-pct:0.15}") double heatPct,
            @Value("${dracul.executor.pace-per-week:10}") int pacePerWeek,
            @Value("${dracul.executor.max-per-sector:5}") int maxPerSector,
            @Value("${dracul.executor.cooldown-days:3}") int cooldownDays,
            MechanismBudget mechanismBudget,
            ConvictionProfile convictionProfile,
            SavingsPlanSettings savingsPlanSettings) {
        this.active = active;
        this.repo = repo;
        this.mapper = mapper;
        this.brokerStopBufferAtr = brokerStopBufferAtr;
        this.maxBrokerStopPct = maxBrokerStopPct;
        this.atrShortPeriod = atrShortPeriod;
        this.riskPct = riskPct;
        this.minConfidence = minConfidence;
        this.maxPositions = maxPositions;
        this.totalBudget = totalBudget;
        this.trancheCount = trancheCount;
        this.heatPct = heatPct;
        this.pacePerWeek = pacePerWeek;
        this.maxPerSector = maxPerSector;
        this.cooldownDays = cooldownDays;
        this.mechanismBudget = mechanismBudget;
        this.convictionProfile = convictionProfile;
        this.savingsPlanSettings = savingsPlanSettings;
    }

    @PostConstruct
    void seed() {
        if (!repo.exists(active)) {
            var params = mapper.createObjectNode()
                    .put("chandelier_mult", 3.0)
                    .put("giveback_pct", 0.35)
                    .put("giveback_active_from_r", 1.5)
                    .put("cooldown_days", cooldownDays)
                    .put("atr_period", 22)
                    .put("soft_confirm_min", 2)
                    .put("confidence_min", minConfidence)
                    .put("max_positions", maxPositions)
                    .put("trim_fractions", "0.33,0.5,1.0")
                    .put("entry_gtd_days", 2)
                    .put("kill_criteria_hard", "structured kill_close_below, single close, "
                            + "KILL_LEVEL_BREACHED veto, 0.5 ATR min distance")
                    .put("broker_stop_buffer_atr", brokerStopBufferAtr)
                    .put("max_broker_stop_pct", maxBrokerStopPct)
                    .put("atr_short_period", atrShortPeriod)
                    .put("risk_pct", riskPct)
                    .put("mechanism_budget_pct", mechanismBudget.spec())
                    .put("total_budget", totalBudget)
                    .put("tranche_count", trancheCount)
                    .put("heat_pct", heatPct)
                    .put("pace_per_week", pacePerWeek)
                    .put("max_per_sector", maxPerSector)
                    .put("exit_profiles", "STANDARD,CONVICTION,MOMENTUM")
                    .put("conviction_emergency_stop_pct", convictionProfile.emergencyStopPct())
                    .put("conviction_target_pct", convictionProfile.targetPct())
                    .put("conviction_target_fraction", convictionProfile.targetFraction())
                    .put("conviction_trail_pct", convictionProfile.trailPct())
                    .put("conviction_min_entry_qty", convictionProfile.minEntryQty())
                    .put("conviction_entry_broker_stop_pct", convictionProfile.entryBrokerStopPct())
                    .put("conviction_position_pct", convictionProfile.positionPct())
                    .put("momentum_position_pct", convictionProfile.momentumPositionPct())
                    .put("momentum_min_entry_qty", convictionProfile.momentumMinEntryQty())
                    .put("savings_plan_monthly_pct", savingsPlanSettings.monthlyPct())
                    .put("savings_plan_max_position_pct", savingsPlanSettings.maxPositionPct())
                    .put("savings_plan_basket_cap_pct", savingsPlanSettings.basketCapPct())
                    .put("savings_plan_limit_premium_pct", savingsPlanSettings.limitPremiumPct())
                    .put("savings_plan_catch_up_weekdays", savingsPlanSettings.catchUpWeekdays())
                    .put("savings_plan_window_start_utc", savingsPlanSettings.windowStartUtc().toString())
                    .put("savings_plan_tif", savingsPlanSettings.tif())
                    .put("savings_plan_place_first", savingsPlanSettings.placeFirst())
                    .put("conviction_r_per_share", "entry_price x emergency_stop_pct");
            // seed() only inserts when the version string is NEW, so this text is written once and
            // is then permanent for the version it describes -- it is the audit record of what
            // that version changed, and prod verification asserts it verbatim.
            //
            // exec-v1.2 history (no longer seeded; prod seeded it on the strigoi-momentum deploy,
            // insert-if-absent keeps that row as first written): "on top of exec-v1.1: exit profile
            // MOMENTUM for mechanism MOMENTUM_12_1 (strigoi-momentum, monthly 12-1 rebalance) --
            // CONVICTION's wide emergency stop (35 % below entry, broker leg at the entry band,
            // widened after the fill) and fixed notional per name (momentum position-pct 0.025 of
            // total-budget, FX-converted, SIZE_TOO_SMALL below min-entry-qty 1), no take-profit, no
            // target-half, no trail, no catastrophe flag, no soft exit, no tranche 2, exit_position
            // rejects it with PROFILE_MANAGED; a position flagged by the strigoi-momentum completion
            // is flattened fully (HARD_REBALANCE: without a close before the close-null skip, with a
            // close after the stop, which wins the reason code); for a MOMENTUM signal, MOMENTUM
            // rows with a committed rebalance exit are excluded from MECHANISM_BUDGET and BUDGET
            // open exposure and from MAX_POSITIONS (the BUDGET cash check still requires cash >=
            // charge); LOW_CONFIDENCE, CHASED_AWAY and BELOW_ANCHOR skipped for MOMENTUM;
            // PACE_LIMIT counts STANDARD entries only and is skipped for CONVICTION and MOMENTUM;
            // max_positions 25 -> 35; MECHANISM_BUDGET MOMENTUM_12_1 0.28; executor max_turns
            // 25 -> 40; all other exec-v1.1 gates unchanged"
            //
            // exec-v1.1 history (no longer seeded; never reached prod on its own -- it shipped
            // together with exec-v1.2): "on top of exec-v1.0 (exit profile CONVICTION for mechanism
            // TECH_CONVICTION, strigoi-tech basket): (1) post-fill widening -- the entry-band broker
            // leg of a filled CONVICTION position is widened to the logical stop by the first
            // maintenance pass after the fill (BROKER_STOP_WIDENED); a broker rejection escalates
            // BROKER_STOP_WIDEN_REJECTED once, is never retried, and leaves the narrow entry-band
            // leg as the effective emergency stop; (2) capital split revision 2026-10-05 --
            // basket-size 12 -> 10, position-pct 0.033 -> 0.03 (ten names at 3 % each),
            // MECHANISM_BUDGET cap TECH_CONVICTION 0.44 -> 0.33 (10 x 3 % + ~10 % headroom for
            // EUR/USD drift); all other exec-v1.0 gates (CORRELATED, CONCENTRATION and HEAT_LIMIT
            // skipped for the profile, exit_position rejects the profile with PROFILE_MANAGED,
            // partial exits repoint the leg rows) unchanged"
            // (the 0.33 above is history: exec-v1.3 raised TECH_CONVICTION to 0.50 for the
            // Tech-Sparplan basket cap)
            //
            // exec-v1.0 history (no longer seeded; prod seeded this verbatim on 2026-10-04, insert-
            // if-absent means changing this string never reaches that row -- so it is restored here
            // exactly as main b305cbd8 had it, not edited in place): "exit profile CONVICTION for
            // mechanism TECH_CONVICTION (strigoi-tech basket): logical emergency stop 35% below
            // entry with the broker leg at the entry band until widened, fixed notional per name
            // (position-pct of total-budget, FX-converted, SIZE_TOO_SMALL below min-entry-qty), no
            // take-profit, half sold at a close of +30% (HARD_TARGET_HALF), the rest trailed 30%
            // below the highest close, a flagged catastrophe flattens (HARD_CATASTROPHE);
            // CORRELATED, CONCENTRATION and HEAT_LIMIT skipped for the profile, BUDGET and
            // MECHANISM_BUDGET charge the profile notional (TECH_CONVICTION 0.44); exit_position
            // rejects the profile (PROFILE_MANAGED); partial exits repoint the leg rows; STANDARD
            // unchanged from exec-v0.9"
            //
            // exec-v0.9 history (no longer seeded): "structured kill level: only the
            // hunter-authored kill_close_below is code-enforced (BUY, one daily close strictly
            // below it -> HARD_KILL_CRITERIA); free-text kill_criteria are no longer parsed by
            // the executor; place-entry rejects a fresh entry whose level is at or above the
            // order price (KILL_LEVEL_BREACHED) and drops a level closer than 0.5 x atr_effective
            // (too_tight) or breached on an adopted order/fill (breached_at_adoption); all other
            // gates unchanged from exec-v0.8"
            //
            // exec-v0.8 history (no longer seeded): "cooldown after exit shortened from 10 to 3
            // days: COOLDOWN-vetoed signals averaged +1.17 R after 20 days (16/16 positive, n=16);
            // all other gates unchanged from exec-v0.7"
            //
            // exec-v0.7 history (no longer seeded): "paper capital scale-up for learning
            // throughput: total_budget 100000, tranche_count 25 (tranche 4000), risk_pct 0.005,
            // heat_pct 0.15, max_positions 25, pace_per_week 10, max_per_sector 5; mechanism
            // budgets unchanged in pct"
            //
            // exec-v0.6 history (no longer seeded): "confidence floor 0.40; confidence withheld
            // from the LLM queue and dropped from ranking (freshness first); MECHANISM_BUDGET
            // entry cap (MERGER_ARB 20%, QUALITY_52W_LOW 15% of budget), transient like
            // MAX_POSITIONS; max_positions 8"
            repo.upsert(new RuleVersion(active, LocalDate.now().toString(),
                    "on top of exec-v1.2: Tech-Sparplan for exit profile CONVICTION -- "
                            + "on the first weekday of the month (catch-up weekdays 2-3, UTC calendar) inside the "
                            + "closed-market window from 21:15 UTC, code splits monthly-pct 0.02 of total-budget "
                            + "equally across eligible CONVICTION positions (per-position cap 0.08 and basket cap 0.50 "
                            + "of total-budget at market value, fractional carry per position, at any price incl. "
                            + "below entry) and buys them as a bracket add (limit = close x 1.02, child stop at the "
                            + "entry band, tif gtc|day); after one US session consolidation books one position, one "
                            + "leg, one stop at the new average x 0.65 (the stop may move down, initial_stop "
                            + "unchanged; order cancel-first|place-first); the add is never a tranche; if the pre-add "
                            + "emergency stop fills while an add is in flight the add shares are sold too (D8); "
                            + "in-flight positions are excluded from hard triggers and the ratchet (UNPROTECTED ones "
                            + "keep the stop breach and catastrophe checks); R per share for CONVICTION = entry_price "
                            + "x emergency-stop-pct, outcome and pattern scoring use the persisted r_value; "
                            + "MECHANISM_BUDGET TECH_CONVICTION 0.33 -> 0.50 (cost-based, new names only); "
                            + "ENTRY_PRICE_SYNC compares at scale 6; all other exec-v1.2 gates unchanged",
                    null, params));
        }
    }

    public String active() {
        return active;
    }
}
