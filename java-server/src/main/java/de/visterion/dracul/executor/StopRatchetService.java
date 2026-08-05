package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.BrokerUnavailableException;
import de.visterion.dracul.executor.broker.ExecutionGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Trailing chandelier stop: raises (BUY) or lowers (SELL) the active stop toward the market as
 * a position runs in its favor, but never moves it against the position. {@link StopRatchetGuard}
 * is the single choke point enforcing that — this service must never call the gateway or update
 * the position book when the guard denies the move.
 *
 * <p><b>The gateway takes the BRACKET id, not a leg id</b> — passing {@code stopOrderId} instead
 * meant the ratchet never moved a single stop until this was fixed on 2026-07-26; see the comment
 * at the {@code modifyBracket} call site below for the full account before editing that call.
 *
 * <p>Beyond the two trivial skips for missing inputs (no {@code highestPrice} recorded yet, no ATR
 * for the symbol), four conditions stop a position short of the broker, in this order and each
 * with {@code continue} so one position never aborts the rest of the book:
 * <ul>
 *   <li>the guard denies a non-improving move — silent, the normal case;</li>
 *   <li>the rounded chandelier is on the wrong side of the last close (or no close is known) —
 *       silent, a regular "not yet" state owned by the soft trigger;</li>
 *   <li>a tranche 2 is open but one of its two stop legs has no id on the record —
 *       {@code ESCALATE / TRANCHE_RATCHET_UNSUPPORTED}. A leg that cannot be NAMED cannot be
 *       moved without guessing, and a silent partial ratchet would leave the book claiming a stop
 *       half the position does not have. With both ids present, both legs are ratcheted to the
 *       same level instead — see {@link #ratchetTwoLegs};</li>
 *   <li>{@code brokerOrderId} is null — {@code ESCALATE / NO_BRACKET_ID}.</li>
 * </ul>
 *
 * <p>A two-leg ratchet whose FIRST leg moved and whose second did not escalates
 * {@code PARTIAL_TRANCHE_RATCHET} and leaves {@code active_stop} at the OLD value — the only level
 * that is true of the whole position. Never report a partial as a success.
 *
 * <p>Both escalations sit AFTER the guard and after the market-side check, so they repeat on every
 * maintenance run for as long as the condition holds AND a better stop is actually available —
 * the active stop is deliberately left untouched. That is intended: a position that can never be
 * ratcheted must stay loudly visible. Do not "fix" it by moving a check ahead of the guard.
 *
 * <p>On {@link BrokerUnavailableException} during {@code modifyBracket}, a TRANSIENT failure
 * (rate limit / HTTP 429) is retried inside the same run with an exponential backoff, bounded by
 * {@code ratchet-retry-attempts} and a pass-wide wall-clock budget; anything else escalates on the
 * first attempt. Once the attempts or the budget are spent, this escalates via the decision log
 * and leaves the old stop in place — mirrors {@link ReconcileService} and
 * {@link HardTriggerService}'s idiom. Every escalation row carries the position id in
 * {@code order_json} (since {@code decision_log} has no position column) plus the position's
 * signal and agent attribution. See {@link #modifyWithRetry} for why the retry exists and why it
 * is an allow-list.
 *
 * <p><b>Broker first, book second.</b> {@code positionRepo.updateMaintenance} runs only after
 * {@code modifyBracket} returned — the book never claims a stop the broker did not confirm.
 */
@Service
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class StopRatchetService {

    private static final Logger log = LoggerFactory.getLogger(StopRatchetService.class);

    private final ExecutionGateway gateway;
    private final ExecutorPositionRepository positionRepo;
    private final DecisionLogRepository decisionRepo;
    private final RuleVersionProvider ruleVersions;
    private final StopRatchetGuard guard;
    private final ObjectMapper mapper;
    private final ExecutorNotifier executorNotifier;
    private final double chandelierMult;
    private final int retryAttempts;
    private final long retryBackoffMs;
    private final long retryBudgetMs;

    public StopRatchetService(
            ExecutionGateway gateway,
            ExecutorPositionRepository positionRepo,
            DecisionLogRepository decisionRepo,
            RuleVersionProvider ruleVersions,
            StopRatchetGuard guard,
            ObjectMapper mapper,
            ExecutorNotifier executorNotifier,
            @Value("${dracul.executor.chandelier-mult:3.0}") double chandelierMult,
            @Value("${dracul.executor.ratchet-retry-attempts:3}") int retryAttempts,
            @Value("${dracul.executor.ratchet-retry-backoff-ms:500}") long retryBackoffMs,
            @Value("${dracul.executor.ratchet-retry-budget-ms:5000}") long retryBudgetMs) {
        this.gateway = gateway;
        this.positionRepo = positionRepo;
        this.decisionRepo = decisionRepo;
        this.ruleVersions = ruleVersions;
        this.guard = guard;
        this.mapper = mapper;
        this.executorNotifier = executorNotifier;
        this.chandelierMult = chandelierMult;
        this.retryAttempts = Math.max(1, retryAttempts);
        this.retryBackoffMs = Math.max(0, retryBackoffMs);
        this.retryBudgetMs = Math.max(0, retryBudgetMs);
    }

    /** Backoff seam — overridden in tests so retry assertions neither sleep nor guess at timing. */
    protected void backoff(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void ratchet(List<ExecutorPosition> openPositions, Map<String, BigDecimal> atrBySymbol,
            Map<String, BigDecimal> closeBySymbol, String runId) {
        // Wall-clock ceiling for ALL retrying in this pass, shared across every position. The
        // whole ratchet runs inside the agent's 30s fetch_open_positions tool call, so a
        // per-position budget would multiply with the size of the book into a tool timeout.
        // It measures elapsed time in the pass, not time spent retrying, so a slow or hung broker
        // spends the budget on its first attempt and is not retried, while a fast 429 (~1 ms in
        // production) leaves ample room.
        RetryBudget budget = new RetryBudget(retryBudgetMs);

        for (ExecutorPosition p : openPositions) {
            if (p.highestPrice() == null) continue;
            BigDecimal atr = atrBySymbol.get(p.symbol());
            if (atr == null) continue;

            BigDecimal chandelier = computeChandelier(p, atr);
            if (!guard.permit(p.activeStop(), chandelier, p.side())) continue;

            // The guard only compares against the OLD stop, never against the market. If the price
            // fell more than chandelier-mult x ATR off the high while staying above the hard stop,
            // the chandelier sits on the wrong side of the market. Skip silently — the soft trigger
            // owns that state. The reference is a bar close, not a live quote, so this reduces the
            // risk rather than eliminating it; the executor runs after the US close, so the gap is
            // small. Placed BEFORE the escalations below: only what was actually sendable escalates.
            BigDecimal price = closeBySymbol.get(p.symbol());
            if (price == null) continue;
            boolean safeSide = "SELL".equals(p.side())
                    ? chandelier.compareTo(price) > 0
                    : chandelier.compareTo(price) < 0;
            if (!safeSide) continue;

            BigDecimal oldStop = p.activeStop();

            boolean twoStopLegs = !p.stopLegsCollapsed()
                    && (p.tranche() >= 2 || p.tranche2OrderId() != null || p.tranche2StopOrderId() != null);
            if (twoStopLegs) {
                if (!ratchetTwoLegs(p, chandelier, runId, budget)) continue;

                positionRepo.updateMaintenance(p.id(), p.highestPrice(), p.mfeR(), p.softConfirmCount(),
                        chandelier, null);
                recordRatchet(p, atr, chandelier, runId);
                executorNotifier.notifyStopRatchet(p, oldStop, chandelier, p.connection());
                continue;
            }

            // Agora resolves the stop leg FROM the bracket id — pre-fill through the parent's
            // embedded RelatedOpenOrders, post-fill through the by-Uic symbol fallback. Handing it
            // the stop LEG id instead fails in both phases: pre-fill the leg isn't a top-level
            // order at all, post-fill it is found but its Oco sibling is the take-profit, so Agora
            // reports "no stop-loss leg". That is why the ratchet never moved a single stop: across
            // every position ever held, active_stop still equalled initial_stop, with recorded
            // BROKER_UNAVAILABLE escalations from 2026-07-13 onward, until this was fixed on
            // 2026-07-26. stopOrderId stays on the record because ReconcileService matches fills
            // with it — it is simply not an address here. Do NOT "restore" stopOrderId.
            String bracketId = p.brokerOrderId();
            if (bracketId == null) {
                escalate(p, runId, "NO_BRACKET_ID",
                        "stop ratchet cannot address the bracket: broker_order_id is null");
                continue;
            }
            if (!modifyWithRetry(p, bracketId, chandelier, runId, budget)) continue;

            positionRepo.updateMaintenance(p.id(), p.highestPrice(), p.mfeR(), p.softConfirmCount(),
                    chandelier, null);

            recordRatchet(p, atr, chandelier, runId);

            executorNotifier.notifyStopRatchet(p, oldStop, chandelier, p.connection());
        }
    }

    /**
     * Ratchets BOTH stop legs of a position that was built in two tranches. Returns true only when
     * the broker accepted both; false when the position was escalated and the book must not move.
     *
     * <p><b>One level, not two.</b> A protective stop is a price level on the underlying, not a
     * per-tranche quantity: {@link #computeChandelier} is a function of {@code highestPrice} and
     * ATR and never reads {@code entryPrice}, so there is no per-tranche chandelier to compute in
     * the first place. Both legs therefore go to the same price. That also keeps the book honest —
     * {@code active_stop} is ONE column that {@code stopguard} reads for the whole position, and it
     * can only be true of every share behind it if every share sits on the same level.
     *
     * <p><b>Both legs are addressed BY NAME.</b> Post-fill the entry ids are gone and Agora's
     * by-symbol fallback keeps only the LAST stop leg it finds on the instrument, so two unnamed
     * modifies would patch one leg twice and the other never — while still reporting accepted.
     * That is exactly the silent partial this used to escalate rather than risk. Agora's
     * {@code modify_bracket} now takes an explicit {@code stopOrderId}, and both ids are on the
     * record: verified on the paper book 2026-08-04, where a 46-share two-tranche position held
     * {@code stop_order_id} and {@code tranche2_stop_order_id} for two working 24- and 22-share
     * stop orders on the same instrument. The older claim that "Saxo returns no leg ids" was wrong.
     *
     * <p><b>Without both ids there is still nothing to address</b>, and the old
     * {@code TRANCHE_RATCHET_UNSUPPORTED} escalation stands unchanged — a broker that reports no
     * leg id leaves no honest way to move the right stop.
     *
     * <p><b>A collapsed position never reaches this method.</b> A trim can fold two stop legs into
     * one because the remainder was too small to give each leg at least one share; the book then
     * records {@code stop_legs_collapsed} and only one leg genuinely exists at the broker any more.
     * {@link #ratchet} routes that case around this method entirely and ratchets the single
     * surviving leg through the ordinary single-tranche path instead — sending a second modify for
     * a leg the broker no longer has would either fail loudly or, worse, silently no-op. The
     * escalation here stays for the case it was written for: two legs genuinely exist and one id is
     * unknown, which is a bug on the book, not a collapse.
     *
     * <p><b>One leg up, one leg not, is reported as PARTIAL — and the book keeps the OLD stop.</b>
     * Broker first, book second holds per leg: after a leg-1 success and a leg-2 failure the
     * position really is half-protected at the new level and half at the old one, so the only
     * value that is true of all of it is the old stop. Writing the new one would make stopguard
     * trust a protection part of the position does not have; that is the bug class this escalates
     * instead of hiding. The next maintenance pass re-sends both legs (the first is idempotent at
     * the same price) and recovers on its own once the broker cooperates.
     */
    private boolean ratchetTwoLegs(ExecutorPosition p, BigDecimal chandelier, String runId, RetryBudget budget) {
        String leg1 = p.stopOrderId();
        String leg2 = p.tranche2StopOrderId();
        if (leg1 == null || leg2 == null) {
            escalate(p, runId, "TRANCHE_RATCHET_UNSUPPORTED",
                    "stop ratchet unsupported while a tranche 2 is open: both stop legs must be named "
                            + "to be moved unambiguously, but stop_order_id=" + leg1
                            + " and tranche2_stop_order_id=" + leg2);
            return false;
        }

        // Bracket ids are context only once a leg id is given; the tranche-2 entry id may be gone
        // (post-fill) or never recorded, so fall back to the position's own bracket id.
        String bracket1 = p.brokerOrderId();
        String bracket2 = p.tranche2OrderId() != null ? p.tranche2OrderId() : p.brokerOrderId();
        if (bracket1 == null || bracket2 == null) {
            escalate(p, runId, "NO_BRACKET_ID",
                    "stop ratchet cannot address the bracket: broker_order_id is null");
            return false;
        }

        if (!modifyWithRetry(p, bracket1, leg1, chandelier, runId, budget)) return false;
        if (!modifyWithRetry(p, bracket2, leg2, chandelier, runId, budget)) {
            ObjectNode order = mapper.createObjectNode();
            order.put("position_id", p.id());
            order.put("moved_stop_order_id", leg1);
            order.put("unmoved_stop_order_id", leg2);
            order.put("attempted_stop", chandelier);
            order.put("active_stop", p.activeStop());
            decisionRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                    "MAINTENANCE", p.sourceSignalId(), p.sourceAgent(), null, p.symbol(), null, null,
                    "ESCALATE", "PARTIAL_TRANCHE_RATCHET", order,
                    "partial stop ratchet: leg " + leg1 + " moved to " + chandelier.toPlainString()
                            + " but leg " + leg2 + " did not; active_stop stays at "
                            + (p.activeStop() == null ? "null" : p.activeStop().toPlainString())
                            + " because it must hold for the whole position",
                    null, null, null));
            return false;
        }
        return true;
    }

    /**
     * Sends the modify, retrying only genuinely TRANSIENT broker failures. Returns true when the
     * broker accepted; false when the position was escalated and must be skipped.
     *
     * <p>Why retry at all: on 2026-08-03 a Saxo {@code RateLimitExceeded} (HTTP 429) on the stop
     * PATCH escalated straight to a HIGH alarm, and the byte-identical PATCH succeeded on the next
     * run 12 minutes later. The stop was never lost, but for 12 minutes the ratchet had simply not
     * been applied. A rate limit says "come back in a moment"; treating it like a defect is what
     * turned a 1 ms hiccup into a 12-minute gap.
     *
     * <p>Why only transient ones: a structural rejection such as {@code LEG_NOT_FOUND} (seen on
     * 2026-07-26) fails identically on every attempt. Retrying it would delay the escalation that
     * is the correct response, and hammer the broker while doing so — {@link #isTransient} is
     * therefore an allow-list of rate-limit signatures, never a deny-list.
     *
     * <p>The book is written by the caller only AFTER this returns true — the broker confirms
     * first, the DB follows. Do not reorder that: an active_stop written ahead of confirmation
     * would make Dracul's book (and stopguard, which trusts it) claim a protection the broker does
     * not have.
     */
    private boolean modifyWithRetry(ExecutorPosition p, String bracketId, BigDecimal chandelier,
            String runId, RetryBudget budget) {
        return modifyWithRetry(p, bracketId, null, chandelier, runId, budget);
    }

    /** {@code stopOrderId} null = let Agora resolve the leg (single-tranche, unchanged behaviour);
     *  non-null = address that exact stop leg (two-tranche, see {@link #ratchetTwoLegs}). */
    private boolean modifyWithRetry(ExecutorPosition p, String bracketId, String stopOrderId,
            BigDecimal chandelier, String runId, RetryBudget budget) {
        for (int attempt = 1; ; attempt++) {
            try {
                gateway.modifyBracket(p.connection(), bracketId, p.symbol(), chandelier, null,
                        stopOrderId, null);
                return true;
            } catch (BrokerUnavailableException e) {
                boolean mayRetry = attempt < retryAttempts && isTransient(e);
                long wait = retryBackoffMs << Math.min(attempt - 1, 16);   // 500, 1000, 2000, …
                if (mayRetry && budget.consume(wait)) {
                    log.warn("stop-ratchet modify for {} (position {}) hit a transient broker "
                                    + "failure on attempt {}/{}, retrying in {} ms: {}",
                            p.symbol(), p.id(), attempt, retryAttempts, wait, e.getMessage());
                    backoff(wait);
                    continue;
                }
                escalate(p, runId, "BROKER_UNAVAILABLE",
                        "broker unavailable during stop-ratchet modify"
                                + (stopOrderId == null ? "" : " of stop leg " + stopOrderId)
                                + " after " + attempt
                                + " attempt" + (attempt == 1 ? "" : "s") + ": " + e.getMessage());
                return false;
            }
        }
    }

    /**
     * Transient = the broker asked us to slow down, nothing about this request is wrong.
     *
     * <p>Deliberately an ALLOW-list: anything unrecognised escalates on the first attempt, which
     * is the safe default for a stop-protection path. Widen this only against a message or status
     * actually observed in the Agora provider log.
     *
     * <p><b>The message is not the only signal, and treating it as one missed a whole class of
     * real 429.</b> Agora surfaces its own rate limit as {@code available:false} with an
     * {@code error} string ("… rate limited (HTTP 429) …") and its business rejections as
     * {@code accepted:false} with a reject code — both of which arrive in the message. But a rate
     * limit produced by the TRANSPORT arrives as {@code HttpClientErrorException$TooManyRequests}
     * wrapped by {@code AgoraExecutionGateway.call}, and the status then lives only in the cause
     * chain. So the chain is walked, and a {@code RestClientResponseException} carrying 429 counts
     * regardless of what any message says.
     *
     * <p><b>Why not {@code contains("429")}.</b> Those three digits appear in things that are not
     * statuses: a reject text echoing a price near $429, a 10-digit order id, a share quantity.
     * Retrying a structural rejection burns the stop-protection budget and delays the escalation,
     * and the estimated false-positive rate was ~0.8 % of reject messages. Every message that
     * really is a rate limit says so — {@code HTTP 429}, {@code status 429}, a leading
     * {@code 429 Too Many Requests}, or an explicit rate-limit phrase — so the digits are only
     * accepted in one of those shapes.
     */
    private static boolean isTransient(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof org.springframework.web.client.RestClientResponseException r
                    && r.getStatusCode().value() == 429) {
                return true;
            }
            if (isTransientMessage(t.getMessage())) return true;
        }
        return false;
    }

    /** Package-private so the classification can be exercised directly; see {@link #isTransient}
     *  for why a bare {@code "429"} substring is not enough. */
    static boolean isTransientMessage(String message) {
        if (message == null) return false;
        String m = message.toLowerCase(Locale.ROOT);
        if (m.contains("rate limit") || m.contains("ratelimit")
                || m.contains("too many requests") || m.contains("slow down")) {
            return true;
        }
        // 429 only where it is unambiguously a STATUS: after http/status/code, in parentheses or
        // brackets, or at the very start of the message (the RestClient/HttpStatus rendering).
        return RATE_LIMIT_STATUS.matcher(m).find();
    }

    private static final java.util.regex.Pattern RATE_LIMIT_STATUS = java.util.regex.Pattern.compile(
            "(?:^|\\b(?:http|https|status|code|error)\\b[\\s:=/]*|[(\\[])429\\b");

    /**
     * Monotonic deadline for retrying, measured from where it is CONSTRUCTED — inside
     * {@link #ratchet}, after reconcile, expiry, the indicator fetch and the hard triggers have
     * already run. It therefore bounds the ratchet phase, not the enclosing tool call: whatever
     * those earlier stages spent is invisible to it.
     *
     * <p><b>Two claims that used to stand here were false and are worth naming, because both were
     * load-bearing.</b> It did not "measure total elapsed time in the pass" — see above. And there
     * is <b>no 30 s tool timeout</b> to be bounded by: Vistierie declares
     * {@code webhook_timeout_seconds} on the tool definition and never applies it, and the
     * RestClient that calls the webhook uses {@code SimpleClientHttpRequestFactory} with an
     * INFINITE read timeout. Nothing upstream will cut this call short.
     *
     * <p><b>Which is why the budget is kept, not dropped.</b> Its original justification — "a
     * retry that ends in a tool timeout loses the whole maintenance pass" — was wrong, but the
     * absence of that timeout makes a ceiling MORE necessary, not less: without one, a book full
     * of rate-limited positions multiplies {@code retryAttempts} backoffs per position with
     * nothing at all to stop the pass from running for minutes while the executor's run clock and
     * the model's context both wait on it. The budget is now the only bound that exists. A book
     * that already spent it on legitimate broker work gets no retries, which remains the right
     * trade-off: an unratcheted stop escalates and is visible, an unbounded pass is neither.
     */
    private static final class RetryBudget {
        private final long budgetMs;
        private final long startNanos = System.nanoTime();

        RetryBudget(long budgetMs) {
            this.budgetMs = budgetMs;
        }

        /** True when {@code waitMs} of additional backoff still fits inside the budget. */
        boolean consume(long waitMs) {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            return elapsedMs + waitMs <= budgetMs;
        }
    }

    /**
     * Chandelier level, rounded to two decimals toward the SAFE side: FLOOR for a long (the stop is
     * never raised beyond the computed level), CEILING for a short.
     *
     * <p>Deliberately FLOOR/CEILING and not DOWN/UP — the latter round toward and away from zero,
     * which inverts the intent for a negative level (a huge ATR on a cheap instrument). The guard
     * would reject such a value anyway; this simply says what is meant.
     *
     * <p>Rounding happens HERE, before {@link StopRatchetGuard#permit}, so the value checked, the
     * value sent to the broker and the value written to the book are one and the same — and so a
     * sub-cent improvement is denied instead of producing an empty modify every run.
     *
     * <p><b>{@code highestPrice} is side-aware</b>: it holds the position's <em>favorable</em>
     * price extreme — the highest close for a long, the LOWEST close for a short, which
     * {@link ReconcileService} accumulates with {@code min} on SELL and {@code max} on BUY. That is
     * why the SELL branch below <em>adds</em> the offset and still yields a lowest-low chandelier,
     * matching the {@code "lowestLow + "} basis label in {@link #recordRatchet}. The {@code add} on
     * SELL is correct — do not "fix" it into a subtraction.
     */
    private BigDecimal computeChandelier(ExecutorPosition p, BigDecimal atr) {
        BigDecimal offset = atr.multiply(BigDecimal.valueOf(chandelierMult));
        return "SELL".equals(p.side())
                ? p.highestPrice().add(offset).setScale(2, RoundingMode.CEILING)
                : p.highestPrice().subtract(offset).setScale(2, RoundingMode.FLOOR);
    }

    private void recordRatchet(ExecutorPosition p, BigDecimal atr, BigDecimal chandelier, String runId) {
        ObjectNode inputs = mapper.createObjectNode();
        inputs.put("highest_price", p.highestPrice());
        inputs.put("atr", atr);
        inputs.put("chandelier_mult", chandelierMult);
        inputs.put("old_stop", p.activeStop());
        inputs.put("new_stop", chandelier);

        String basisSide = "SELL".equals(p.side()) ? "lowestLow + " : "highestHigh - ";
        ObjectNode order = mapper.createObjectNode();
        order.put("stop_basis", "chandelier: " + basisSide + chandelierMult + "xATR");
        order.put("new_stop", chandelier);

        decisionRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                "MAINTENANCE", null, null, null, p.symbol(), inputs, null,
                "MODIFY_STOP", null, order, null, null, null, null));
    }

    /**
     * One non-throwing escalation row. Carries the signal and agent attribution the position knows,
     * matching {@link EntryExpiryService}'s idiom — an operator triaging {@code decision_log} needs
     * to know which signal and which agent put this position on the book.
     *
     * <p>{@code decision_log} has no position column, so the position id goes into
     * {@code order_json} — the same idiom as {@link ReconcileService}'s {@code PENDING_EXIT_STALE}.
     * The decision-log alarm keys on it; without it a row cannot be attributed to a position.
     */
    private void escalate(ExecutorPosition p, String runId, String reasonCode, String reasoning) {
        ObjectNode order = mapper.createObjectNode();
        order.put("position_id", p.id());
        decisionRepo.insert(new DecisionLog(null, runId, ruleVersions.active(),
                "MAINTENANCE", p.sourceSignalId(), p.sourceAgent(), null, p.symbol(), null, null,
                "ESCALATE", reasonCode, order, reasoning,
                null, null, null));
    }
}
