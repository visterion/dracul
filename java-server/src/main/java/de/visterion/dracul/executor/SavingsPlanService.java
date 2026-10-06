package de.visterion.dracul.executor;

import de.visterion.dracul.executor.SavingsPlanAudit.RowRef;
import de.visterion.dracul.executor.broker.AccountSnapshot;
import de.visterion.dracul.executor.broker.BracketRequest;
import de.visterion.dracul.executor.broker.BrokerOrder;
import de.visterion.dracul.executor.broker.BrokerRejectedException;
import de.visterion.dracul.executor.broker.BrokerUnavailableException;
import de.visterion.dracul.executor.broker.ExecutionGateway;
import de.visterion.dracul.executor.broker.PlacedBracket;
import de.visterion.dracul.marketdata.FxService;
import de.visterion.dracul.strigoi.momentum.MomentumCalendar;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static de.visterion.dracul.executor.SavingsPlanAudit.fields;

/**
 * The Tech-Sparplan add stage (spec 2026-10-06 §4) plus the lease and the in-flight reads the
 * maintenance pipeline needs. Code decides everything; the LLM decides nothing on this path.
 *
 * <p>Order of one plan-day pass: gates (enabled, window, calendar, month done) → lazy carry cleanup →
 * account and FX (both fail the stage CLOSED, the month stays open) → the CONVICTION book read
 * FRESH from {@code positionRepo.findOpen()} (never the in-memory survivors, R2 M7) → month amount
 * (first writer wins) → per position: eligibility, share, carry, qty, intent row, broker call.
 */
@Service
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class SavingsPlanService {

    static final String STAGE_ADD = "add";

    /** The result of one add stage, for the pipeline and tests. */
    public record AddResult(String month, String outcome, String why, int eligible, int placed,
            Map<String, Integer> skipped) {
    }

    /** The first failing eligibility reason and the concrete values compared. */
    record Skip(String reason, String detail) {
    }

    private final ExecutionGateway gateway;
    private final ExecutorPositionRepository positionRepo;
    private final ExecutorPositionLegRepository legRepo;
    private final SavingsPlanRepository savingsRepo;
    private final ExecutorIndicators indicators;
    private final FxService fx;
    private final ConvictionProfile convictionProfile;
    private final SavingsPlanSettings settings;
    private final SavingsPlanAudit audit;
    private final TransactionOperations tx;
    private final BigDecimal totalBudget;
    private final String instrumentCurrency;
    private final int atrPeriod;
    private final int swingPeriod;

    public SavingsPlanService(ExecutionGateway gateway, ExecutorPositionRepository positionRepo,
            ExecutorPositionLegRepository legRepo, SavingsPlanRepository savingsRepo,
            ExecutorIndicators indicators, FxService fx, ConvictionProfile convictionProfile,
            SavingsPlanSettings settings, SavingsPlanAudit audit,
            @Qualifier("savingsPlanTransactions") TransactionOperations tx,
            @Value("${dracul.executor.total-budget:100000}") BigDecimal totalBudget,
            @Value("${dracul.executor.instrument-currency:USD}") String instrumentCurrency,
            @Value("${dracul.executor.atr-period:22}") int atrPeriod,
            @Value("${dracul.executor.swing-period:20}") int swingPeriod) {
        this.gateway = gateway;
        this.positionRepo = positionRepo;
        this.legRepo = legRepo;
        this.savingsRepo = savingsRepo;
        this.indicators = indicators;
        this.fx = fx;
        this.convictionProfile = convictionProfile;
        this.settings = settings;
        this.audit = audit;
        this.tx = tx;
        this.totalBudget = totalBudget;
        this.instrumentCurrency = instrumentCurrency;
        this.atrPeriod = atrPeriod;
        this.swingPeriod = swingPeriod;
    }

    // ---- lease / reads for the pipeline ------------------------------------------------------

    public boolean inWindow(Instant now) {
        return SavingsCalendar.inWindow(now, settings.windowStartUtc());
    }

    public boolean tryLease(String pass) {
        return savingsRepo.tryAcquireLease(pass);
    }

    public void releaseLease(String pass) {
        savingsRepo.releaseLease(pass);
    }

    public String leaseHolder() {
        return savingsRepo.leaseHolder();
    }

    public Map<Long, String> inFlightStatuses() {
        return savingsRepo.inFlightStatusByPosition();
    }

    public Set<Long> positionsTouchedSince(Instant since) {
        return savingsRepo.positionsTouchedSince(since);
    }

    // ---- add stage (spec §4) -----------------------------------------------------------------

    public AddResult addStage(String connection, String runId, String pass, Instant now) {
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        String month = SavingsCalendar.month(today);
        if (!settings.enabled()) return nothing(runId, pass, month, "disabled");
        if (!inWindow(now)) return nothing(runId, pass, month, "outside-window");
        SavingsCalendar.Phase phase = SavingsCalendar.phase(today, settings.catchUpWeekdays());
        SavingsMonth m = savingsRepo.findMonth(month);
        boolean done = m != null && m.completedAt() != null;
        if (phase == SavingsCalendar.Phase.MISSED) {
            if (!done && savingsRepo.markMissedAlerted(month)) {
                audit.escalate(runId, pass, null, null, "SAVINGS_PLAN_MISSED", "WARN",
                        fields("month", month, "weekday_index", MomentumCalendar.weekdayIndex(today)),
                        "savings plan month " + month + " is not done after its catch-up weekdays — "
                                + "no maintenance pass in the window, or a stage failure; the month is "
                                + "skipped, its money stays in carry");
            }
            return nothing(runId, pass, month, done ? "month-done" : "missed");
        }
        if (phase == SavingsCalendar.Phase.NONE) {
            return nothing(runId, pass, month, "not-plan-day");
        }
        if (done) return nothing(runId, pass, month, "month-done");

        int carryDropped = savingsRepo.deleteStaleCarry();

        AccountSnapshot account = readAccount(connection, runId, pass);
        if (account == null || account.cash() == null) {
            return stageSkip(runId, pass, month, "NO_ACCOUNT",
                    account == null ? "account=null" : "cash=null", "no-account");
        }
        BigDecimal fxToAccount = fxToAccount(account.currency());
        if (fxToAccount == null) {
            return stageSkip(runId, pass, month, "FX_MISSING",
                    "pair=" + instrumentCurrency + "->" + account.currency(), "fx-missing");
        }

        List<ExecutorPosition> book = positionRepo.findOpen().stream()
                .filter(p -> connection.equals(p.connection()))
                .filter(p -> p.profile() == ExitProfile.CONVICTION)
                .sorted(Comparator.comparing(ExecutorPosition::id))
                .toList();
        Map<String, BigDecimal> closeBySymbol = closes(book);
        Map<Long, String> inFlight = savingsRepo.inFlightStatusByPosition();

        BigDecimal basketValue = BigDecimal.ZERO;
        Map<Long, Skip> reasons = new LinkedHashMap<>();
        int eligible = 0;
        for (ExecutorPosition p : book) {
            BigDecimal close = closeBySymbol.get(p.symbol());
            BigDecimal priced = close != null ? close : p.entryPrice();   // §4.2: missing close → at cost
            basketValue = basketValue.add(p.qty().multiply(priced).multiply(fxToAccount));
            Skip skip = ineligible(p, close, fxToAccount, inFlight);
            reasons.put(p.id(), skip);
            if (skip == null) eligible++;
        }

        if (m == null || m.monthAmountEur() == null) {
            BigDecimal cap = totalBudget.multiply(settings.basketCapPct());
            BigDecimal headroom = cap.subtract(basketValue);
            BigDecimal amount = totalBudget.multiply(settings.monthlyPct()).min(headroom);
            if (amount.signum() <= 0) {
                savingsRepo.ensureMonth(month, BigDecimal.ZERO, 0);
                savingsRepo.completeMonth(month);
                return stageSkip(runId, pass, month, "CAP_FULL",
                        "basket_value_eur=" + SavingsPlanAudit.plain(basketValue.setScale(2, RoundingMode.HALF_UP))
                                + ">=cap_eur=" + SavingsPlanAudit.plain(cap), "cap-full");
            }
            m = savingsRepo.ensureMonth(month, amount.setScale(6, RoundingMode.HALF_UP), eligible);
        }
        BigDecimal share = (m.candidateCount() == null || m.candidateCount() == 0)
                ? BigDecimal.ZERO
                : m.monthAmountEur().divide(BigDecimal.valueOf(m.candidateCount()), 6, RoundingMode.HALF_UP);

        // Fix round 1 Minor 2: the per-candidate share is fixed on day 1 against that day's
        // candidate count, but a catch-up pass can see MORE eligible candidates (one became
        // eligible after day 1). Without a cap, every late candidate gets a full extra share and
        // the month overpays. Cap what is left to allocate at the month amount minus what earlier
        // passes already committed this month: realised spend (qty × limit_eur) of every row
        // already on the books for this month. A CARRY/NO_CASH skip row's accrued share from an
        // EARLIER pass is not persisted on the row (no limit_eur column for a SKIPPED row), so it
        // is not reflected here — a known gap, not a silent one: it can only under- not over-cap.
        BigDecimal committedFromEarlierPasses = savingsRepo.findByMonth(month).stream()
                .filter(b -> b.qty() != null && b.qty().signum() > 0 && b.limitEur() != null)
                .map(b -> b.qty().multiply(b.limitEur()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal remainingBudget = (m.monthAmountEur() == null ? BigDecimal.ZERO : m.monthAmountEur())
                .subtract(committedFromEarlierPasses).max(BigDecimal.ZERO);

        BigDecimal cash = account.cash();
        int placed = 0;
        int rejected = 0;
        int escalated = 0;
        int raced = 0;
        Map<String, Integer> skipped = new TreeMap<>();
        for (ExecutorPosition p : book) {
            savingsRepo.renewLease(pass);
            if (savingsRepo.existsForMonth(month, p.id())) continue;
            BigDecimal close = closeBySymbol.get(p.symbol());
            Skip skip = reasons.get(p.id());
            if (skip != null) {
                if (recordSkip(runId, pass, month, p, skip, null, Map.of())) skipped.merge(skip.reason(), 1, Integer::sum);
                continue;
            }
            BigDecimal positionBudget = share.min(remainingBudget).max(BigDecimal.ZERO);
            Outcome o = addOne(connection, runId, pass, month, p, close, fxToAccount, positionBudget, cash);
            remainingBudget = remainingBudget.subtract(o.committedEur()).max(BigDecimal.ZERO);
            cash = cash.subtract(o.cashUsed());
            if (o.placed()) placed++;
            if (o.skipReason() != null) skipped.merge(o.skipReason(), 1, Integer::sum);
            if (o.rejected()) rejected++;
            if (o.rejected() || o.indeterminate()) escalated++;
            if (o.raced()) raced++;
        }

        boolean allDone = book.stream().allMatch(p -> savingsRepo.existsForMonth(month, p.id()));
        if (allDone) savingsRepo.completeMonth(month);
        int skippedTotal = skipped.values().stream().mapToInt(Integer::intValue).sum();
        // Fix round 1 Important #1: a pass whose only candidates were a determinate reject or an
        // indeterminate placement wrote a PLACING row and made a broker call — that is NOT
        // did-nothing, and the log line must say so (§8a is the grep anchor for the daily analysis).
        boolean acted = placed + skippedTotal + escalated > 0;
        String why = acted ? "-" : (book.isEmpty() ? "no-candidates" : "all-candidates-already-have-a-row");
        audit.stage(STAGE_ADD, runId, pass, acted ? "acted" : "did-nothing", why,
                fields("month", month, "phase", phase, "amount_eur", m.monthAmountEur(),
                        "candidates", book.size(), "eligible", eligible, "placed", placed,
                        "rejected", rejected, "escalated", escalated, "raced", raced,
                        "skipped", SavingsPlanAudit.renderCounts(skipped), "cash_left_eur", cash,
                        "carry_dropped", carryDropped, "month_completed", allDone));
        if (acted) {
            audit.digest("Sparplan " + month + ": " + placed + " Zukäufe platziert, " + rejected
                    + " abgelehnt, " + escalated + " eskaliert, " + skippedTotal + " übersprungen "
                    + SavingsPlanAudit.renderCounts(skipped));
        }
        return new AddResult(month, acted ? "acted" : "did-nothing", why, eligible, placed, skipped);
    }

    /** What one eligible position did to the pass: placed, skipped (reason), cash consumed, the
     *  amount it took out of (or returns to) the month's remaining budget, and whether it ended in
     *  a determinate broker reject, an indeterminate broker outcome, or a parallel-pass race. */
    private record Outcome(boolean placed, String skipReason, BigDecimal cashUsed, BigDecimal committedEur,
            boolean rejected, boolean indeterminate, boolean raced) {
    }

    private Outcome addOne(String connection, String runId, String pass, String month,
            ExecutorPosition p, BigDecimal close, BigDecimal fxToAccount, BigDecimal share,
            BigDecimal cash) {
        BigDecimal value = p.qty().multiply(close).multiply(fxToAccount);
        BigDecimal capEur = totalBudget.multiply(settings.maxPositionPct());
        BigDecimal positionShare = share.min(capEur.subtract(value)).max(BigDecimal.ZERO)
                .setScale(6, RoundingMode.HALF_UP);
        BigDecimal limit = TickSize.roundEntry("BUY",
                close.multiply(BigDecimal.ONE.add(settings.limitPremiumPct())));
        BigDecimal limitEur = limit.multiply(fxToAccount).setScale(6, RoundingMode.HALF_UP);
        BigDecimal carryBefore = savingsRepo.carryOf(p.id());
        BigDecimal carry = carryBefore.add(positionShare);
        BigDecimal qty = carry.divide(limitEur, 0, RoundingMode.FLOOR);
        BigDecimal avgAfterEst = qty.signum() > 0
                ? p.qty().multiply(p.entryPrice()).add(qty.multiply(limit))
                        .divide(p.qty().add(qty), 6, RoundingMode.HALF_UP)
                : p.entryPrice();
        BigDecimal logicalStop = convictionProfile.emergencyStop("BUY", avgAfterEst);
        Map<String, Object> values = fields("close", close, "share_eur", positionShare,
                "carry_eur_before", carryBefore, "carry_eur", carry, "limit", limit,
                "limit_eur", limitEur, "qty", qty, "avg_after_est", avgAfterEst,
                "logical_stop_after", logicalStop);

        if (logicalStop.compareTo(close) >= 0) {
            Skip s = new Skip("STOP_ABOVE_CLOSE", "stop_after_add=" + SavingsPlanAudit.plain(logicalStop)
                    + ">=close=" + SavingsPlanAudit.plain(close) + " avg_after_est="
                    + SavingsPlanAudit.plain(avgAfterEst));
            boolean ok = recordSkip(runId, pass, month, p, s, null, values);
            return ok ? new Outcome(false, s.reason(), BigDecimal.ZERO, BigDecimal.ZERO, false, false, false)
                    : raced();
        }
        if (qty.signum() == 0) {
            Skip s = new Skip("CARRY", "carry_eur=" + SavingsPlanAudit.plain(carry) + "<limit_eur="
                    + SavingsPlanAudit.plain(limitEur));
            boolean ok = recordSkip(runId, pass, month, p, s, carry, values);
            // positionShare is now committed: it left the remaining month budget for good — it
            // sits in this position's carry, not back in the pool (spec §4.3: the money stays in
            // carry, never retried this month).
            return ok ? new Outcome(false, s.reason(), BigDecimal.ZERO, positionShare, false, false, false)
                    : raced();
        }
        BigDecimal cost = qty.multiply(limitEur).setScale(6, RoundingMode.HALF_UP);
        if (cost.compareTo(cash) > 0) {
            Skip s = new Skip("NO_CASH", "cost_eur=" + SavingsPlanAudit.plain(cost) + ">cash_eur="
                    + SavingsPlanAudit.plain(cash));
            boolean ok = recordSkip(runId, pass, month, p, s, carry, values);
            return ok ? new Outcome(false, s.reason(), BigDecimal.ZERO, positionShare, false, false, false)
                    : raced();
        }

        ExecutorPositionLeg leg = singleOpenLeg(p.id());
        BigDecimal qtyBefore = leg != null ? leg.qty() : p.qty();
        BigDecimal carryAfter = carry.subtract(cost);
        Long id = tx.execute(status -> {
            Long newId = savingsRepo.insertPlacing(month, p.id(), p.symbol(), qty, limit, limitEur,
                    qtyBefore, p.entryPrice(), p.activeStop(), settings.tif());
            if (newId != null) savingsRepo.setCarry(p.id(), carryAfter);
            return newId;
        });
        if (id == null) {
            // Fix round 1 Minor 4: no second "savings-plan stage" line — "exactly one per stage
            // per pass" is the contract. An INFO skip-style line, folded into the single
            // summary's "raced" counter; the row the OTHER pass wrote already carries the trail.
            audit.raced(runId, pass, month, p.id(), p.symbol());
            return raced();
        }
        RowRef ref = new RowRef(id, month, p.id(), p.symbol());
        BigDecimal childStop = convictionProfile.entryBrokerStop("BUY", limit, logicalStop);
        Map<String, Object> intent = new LinkedHashMap<>(values);
        intent.putAll(fields("qty_before", qtyBefore, "avg_before", p.entryPrice(),
                "stop_before", p.activeStop(), "carry_eur_after", carryAfter, "child_stop", childStop,
                "tif", settings.tif()));
        audit.transition(runId, pass, ref, "none", SavingsBuy.PLACING, "SAVINGS_ADD", "PLACING", intent,
                "intent row written before the broker call");

        String clientRef = SavingsBuy.clientRef(p.id(), month);
        BracketRequest req = new BracketRequest(p.symbol(), "BUY", qty, limit, childStop, null,
                clientRef, settings.tif());
        try {
            PlacedBracket pb = audit.broker(STAGE_ADD, runId, pass, "placeBracket",
                    "symbol=" + p.symbol() + " side=BUY qty=" + qty + " limit=" + SavingsPlanAudit.plain(limit)
                            + " child_stop=" + SavingsPlanAudit.plain(childStop) + " tif=" + settings.tif()
                            + " client_ref=" + clientRef,
                    () -> gateway.placeBracket(connection, req),
                    r -> "ids=parent:" + r.bracketId() + ",child:" + r.stopLegId());
            if (savingsRepo.markPlaced(id, pb.bracketId(), pb.stopLegId())) {
                audit.transition(runId, pass, ref, SavingsBuy.PLACING, SavingsBuy.PLACED, "SAVINGS_ADD",
                        "SAVINGS_ADD", fields("qty", qty, "limit", limit, "entry_order_id", pb.bracketId(),
                                "child_stop_order_id", pb.stopLegId(), "tif", settings.tif()),
                        "savings add placed");
            }
            return new Outcome(true, null, cost, cost, false, false, false);
        } catch (BrokerRejectedException e) {
            Boolean rejectedTxOk = tx.execute(status -> {
                if (!savingsRepo.transition(id, SavingsBuy.PLACING, SavingsBuy.REJECTED)) {
                    status.setRollbackOnly();
                    return false;
                }
                savingsRepo.addCarry(p.id(), cost);
                return true;
            });
            if (Boolean.TRUE.equals(rejectedTxOk)) {
                audit.transition(runId, pass, ref, SavingsBuy.PLACING, SavingsBuy.REJECTED, "SAVINGS_ADD",
                        "REJECTED", fields("reject_code", e.rejectCode(), "carry_refund_eur", cost),
                        "determinate broker reject — carry kept, not retried this month");
            }
            audit.escalate(runId, pass, ref, p.symbol(), "SAVINGS_ADD_REJECTED", "WARN",
                    fields("reject_code", e.rejectCode()), "broker rejected the savings add: " + e.getMessage());
            // The refund re-adds `cost` to carry, so carry nets to carryBefore + positionShare —
            // the same "share stayed in carry, not retried this month" shape as CARRY/NO_CASH.
            return new Outcome(false, null, BigDecimal.ZERO, positionShare, true, false, false);
        } catch (BrokerUnavailableException e) {
            audit.escalate(runId, pass, ref, p.symbol(), "SAVINGS_ADD_INDETERMINATE", "WARN",
                    fields("client_ref", clientRef), "no broker verdict on the savings add — the row "
                            + "stays PLACING; the next closed pass adopts it via ordersByRef / broker qty: "
                            + e.getMessage());
            return new Outcome(false, null, cost, cost, false, true, false);
        }
    }

    private static Outcome raced() {
        return new Outcome(false, null, BigDecimal.ZERO, BigDecimal.ZERO, false, false, true);
    }

    /** Writes the SKIPPED row (and, for CARRY/NO_CASH, the accrued carry) in one transaction, then
     *  logs it. @return false when a parallel pass already owns (month, position) */
    private boolean recordSkip(String runId, String pass, String month, ExecutorPosition p, Skip skip,
            BigDecimal accrueCarry, Map<String, Object> values) {
        Long id = tx.execute(status -> {
            Long sid = savingsRepo.insertSkipped(month, p.id(), p.symbol(), p.qty(), p.entryPrice(),
                    p.activeStop(), skip.reason());
            if (sid != null && accrueCarry != null) savingsRepo.setCarry(p.id(), accrueCarry);
            return sid;
        });
        if (id == null) return false;
        Map<String, Object> v = new LinkedHashMap<>(values);
        v.putAll(fields("qty_before", p.qty(), "avg_before", p.entryPrice(), "stop_before", p.activeStop()));
        audit.skip(runId, pass, new RowRef(id, month, p.id(), p.symbol()), skip.reason(), skip.detail(), v);
        return true;
    }

    /** §4.3 — the first failing reason, with the compared values. */
    Skip ineligible(ExecutorPosition p, BigDecimal close, BigDecimal fxToAccount, Map<Long, String> inFlight) {
        if (p.entryFilledAt() == null) return new Skip("UNFILLED", "entry_filled_at=null");
        if (p.pendingExitReason() != null) return new Skip("PENDING_EXIT", "pending_exit_reason=" + p.pendingExitReason());
        if (p.trimCount() > 0 || p.pendingTrimOrderId() != null) {
            return new Skip("TRIMMED", "trim_count=" + p.trimCount() + " pending_trim_order_id=" + p.pendingTrimOrderId());
        }
        if (p.catastropheReason() != null) return new Skip("CATASTROPHE", "catastrophe_reason=" + p.catastropheReason());
        List<ExecutorPositionLeg> legs = legRepo.findOpenByPosition(p.id());
        ExecutorPositionLeg first = legs.isEmpty() ? null : legs.getFirst();
        if (legs.size() != 1 || first.stopOrderId() == null || first.tranche() != 1 || p.tranche() != 1) {
            return new Skip("LEGS", "open_legs=" + legs.size() + " leg_stop="
                    + (first == null ? null : first.stopOrderId()) + " leg_tranche="
                    + (first == null ? null : first.tranche()) + " position_tranche=" + p.tranche());
        }
        if (inFlight.containsKey(p.id())) return new Skip("IN_FLIGHT", "in_flight_status=" + inFlight.get(p.id()));
        if (close == null) return new Skip("DATA", "close=null");
        BigDecimal value = p.qty().multiply(close).multiply(fxToAccount).setScale(6, RoundingMode.HALF_UP);
        BigDecimal cap = totalBudget.multiply(settings.maxPositionPct());
        if (value.compareTo(cap) >= 0) {
            return new Skip("CAP", "value_eur=" + SavingsPlanAudit.plain(value) + ">=cap_eur="
                    + SavingsPlanAudit.plain(cap) + " weight="
                    + value.divide(totalBudget, 4, RoundingMode.HALF_UP).toPlainString() + ">="
                    + SavingsPlanAudit.plain(settings.maxPositionPct()));
        }
        return null;
    }

    private AddResult nothing(String runId, String pass, String month, String why) {
        audit.stage(STAGE_ADD, runId, pass, "did-nothing", why, fields("month", month));
        return new AddResult(month, "did-nothing", why, 0, 0, Map.of());
    }

    private AddResult stageSkip(String runId, String pass, String month, String reason, String detail,
            String why) {
        audit.stageSkip(runId, pass, month, reason, detail);
        audit.stage(STAGE_ADD, runId, pass, "did-nothing", why, fields("month", month, "detail", detail));
        return new AddResult(month, "did-nothing", why, 0, 0, Map.of());
    }

    private AccountSnapshot readAccount(String connection, String runId, String pass) {
        try {
            return audit.broker(STAGE_ADD, runId, pass, "account", "connection=" + connection,
                    () -> gateway.account(connection),
                    a -> a == null ? "account=null" : "cash=" + SavingsPlanAudit.plain(a.cash())
                            + " currency=" + a.currency());
        } catch (BrokerUnavailableException e) {
            return null;
        }
    }

    /** USD → account currency, or null (fail closed, never 1 — unlike EntryContextAssembler). */
    private BigDecimal fxToAccount(String accountCurrency) {
        if (accountCurrency == null) return null;
        if (accountCurrency.equalsIgnoreCase(instrumentCurrency)) return BigDecimal.ONE;
        fx.warm(instrumentCurrency, accountCurrency);
        if (!fx.hasRate(instrumentCurrency, accountCurrency)) return null;
        return fx.convert(BigDecimal.ONE, instrumentCurrency, accountCurrency);
    }

    /** The stage fetches its own closes (spec §3.2, R3 Minor 10). */
    private Map<String, BigDecimal> closes(List<ExecutorPosition> book) {
        Map<String, BigDecimal> out = new HashMap<>();
        for (ExecutorPosition p : book) {
            if (out.containsKey(p.symbol())) continue;
            ExecutorIndicators.Levels lv = indicators.levels(p.symbol(), atrPeriod, swingPeriod);
            out.put(p.symbol(), lv != null && lv.available() ? lv.referencePrice() : null);
        }
        return out;
    }

    /** §5.3 SAVINGS_ADD_STALE: a PLACING/PLACED row ≥ 2 weekdays after its NY trade date with no
     *  closed pass that consolidated it. Runs on EVERY pass (no window, no lease); once per row. */
    public int staleCheck(String connection, String runId, String pass, Instant now) {
        int stale = 0;
        int raised = 0;
        for (SavingsBuy row : savingsRepo.findInFlight()) {
            if (!SavingsBuy.PLACING.equals(row.status()) && !SavingsBuy.PLACED.equals(row.status())) continue;
            if (!SavingsCalendar.stale(row.createdAt(), now)) continue;
            ExecutorPosition p = positionRepo.findById(row.positionId());
            if (p == null || !connection.equals(p.connection())) continue;
            stale++;
            if (audit.escalatedFor("SAVINGS_ADD_STALE", row.id())) continue;
            LocalDate tradeDate = SavingsCalendar.nyTradeDate(row.createdAt());
            audit.escalate(runId, pass, RowRef.of(row), row.symbol(), "SAVINGS_ADD_STALE", "CRITICAL",
                    fields("status", row.status(), "ny_trade_date", tradeDate,
                            "weekdays_since", SavingsCalendar.weekdaysAfter(tradeDate, SavingsCalendar.nyTradeDate(now)),
                            "client_ref", row.clientRef(), "tif", row.tif()),
                    "savings add " + row.clientRef() + " is still " + row.status()
                            + " two weekdays after it was placed — no closed maintenance pass consolidated it");
            raised++;
        }
        audit.stage("stale", runId, pass, raised > 0 ? "acted" : "did-nothing",
                raised > 0 ? "-" : (stale > 0 ? "already-escalated" : "no-stale-rows"),
                fields("stale", stale, "escalated", raised));
        return raised;
    }

    /** §6.3 STOP_NOT_LIVE: in each closed pass every OPEN, filled CONVICTION position without an
     *  in-flight add, pending trim or pending exit must have its single leg's stop LIVE
     *  (AdoptionCandidates.isLive — a "notworking" child maps to WORKING and is NOT live). CRITICAL
     *  every pass while it is not. */
    public int checkStopsLive(String connection, String runId, String pass) {
        Map<Long, String> inFlight = savingsRepo.inFlightStatusByPosition();
        List<ExecutorPosition> book = positionRepo.findOpen().stream()
                .filter(p -> connection.equals(p.connection()))
                .filter(p -> p.profile() == ExitProfile.CONVICTION)
                .filter(p -> p.entryFilledAt() != null)
                .filter(p -> p.pendingTrimOrderId() == null && p.pendingExitReason() == null)
                .filter(p -> !inFlight.containsKey(p.id()))
                .toList();
        if (book.isEmpty()) {
            audit.stage("stop-live", runId, pass, "did-nothing", "no-positions-to-check", fields("checked", 0));
            return 0;
        }
        List<BrokerOrder> open = audit.broker("stop-live", runId, pass, "orders", "connection=" + connection,
                () -> gateway.orders(connection), l -> "count=" + l.size());
        int notLive = 0;
        for (ExecutorPosition p : book) {
            List<ExecutorPositionLeg> legs = legRepo.findOpenByPosition(p.id());
            String stopId = legs.size() == 1 && legs.getFirst().stopOrderId() != null
                    ? legs.getFirst().stopOrderId() : p.stopOrderId();
            boolean live = stopId != null && open.stream()
                    .anyMatch(o -> stopId.equals(o.orderId()) && AdoptionCandidates.isLive(o));
            if (live) continue;
            notLive++;
            String raw = stopId == null ? null : open.stream().filter(o -> stopId.equals(o.orderId()))
                    .map(BrokerOrder::rawStatus).findFirst().orElse("absent");
            audit.escalate(runId, pass, null, p.symbol(), "STOP_NOT_LIVE", "CRITICAL",
                    fields("position", p.id(), "stop_order_id", stopId, "raw_status", raw, "open_legs", legs.size()),
                    "the single stop of CONVICTION position " + p.symbol() + " (" + stopId + ") is not live at the broker");
        }
        audit.stage("stop-live", runId, pass, notLive > 0 ? "acted" : "did-nothing",
                notLive > 0 ? "-" : "all-stops-live", fields("checked", book.size(), "not_live", notLive));
        return notLive;
    }

    private ExecutorPositionLeg singleOpenLeg(long positionId) {
        List<ExecutorPositionLeg> legs = legRepo.findOpenByPosition(positionId);
        return legs.size() == 1 ? legs.getFirst() : null;
    }
}
