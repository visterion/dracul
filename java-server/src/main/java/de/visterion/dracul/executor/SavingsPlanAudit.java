package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.BrokerRejectedException;
import de.visterion.dracul.executor.broker.BrokerUnavailableException;
import de.visterion.dracul.notify.TelegramNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The ONE vocabulary of the Tech-Sparplan trace (spec 2026-10-06 §8a): every status change, skip,
 * broker call, stage summary and escalation goes through here, as a stable-prefix log line AND (for
 * transitions, skips, escalations) a {@code decision_log} row whose {@code order_json} carries the
 * same values — so the DB alone reconstructs a row's life. Prefixes are a contract (see the plan's
 * "§ Logging / Diagnose" table); never rename one.
 */
@Component
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class SavingsPlanAudit {

    static final String TRIGGER = "SAVINGS_PLAN";
    private static final Logger log = LoggerFactory.getLogger(SavingsPlanAudit.class);

    /** The identity of a savings_plan_buy row in every line. */
    public record RowRef(Long id, String month, long positionId, String symbol) {
        public static RowRef of(SavingsBuy b) {
            return new RowRef(b.id(), b.month(), b.positionId(), b.symbol());
        }
    }

    private final DecisionLogRepository decisionRepo;
    private final RuleVersionProvider ruleVersions;
    private final ObjectMapper mapper;
    private final TelegramNotifier telegram;

    public SavingsPlanAudit(DecisionLogRepository decisionRepo, RuleVersionProvider ruleVersions,
            ObjectMapper mapper, TelegramNotifier telegram) {
        this.decisionRepo = decisionRepo;
        this.ruleVersions = ruleVersions;
        this.mapper = mapper;
        this.telegram = telegram;
    }

    /** Ordered key/value pairs: {@code fields("qty", qty, "limit", limit)}. */
    public static Map<String, Object> fields(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) m.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        return m;
    }

    static String plain(Object v) {
        if (v == null) return "null";
        if (v instanceof BigDecimal b) return b.stripTrailingZeros().toPlainString();
        return String.valueOf(v).replace(' ', '_');
    }

    static String kv(Map<String, Object> f) {
        StringJoiner j = new StringJoiner(" ");
        f.forEach((k, v) -> j.add(k + "=" + plain(v)));
        return j.toString();
    }

    /** {@code {REASON:n,...}} — explicit, so no {@code Map.toString()} space survives into
     *  {@link #plain} as a stray {@code _}. */
    static String renderCounts(Map<String, Integer> counts) {
        StringJoiner j = new StringJoiner(",", "{", "}");
        counts.forEach((k, v) -> j.add(k + ":" + v));
        return j.toString();
    }

    /** One status change: INFO line + decision row (action/reason per the plan's code table). */
    public void transition(String runId, String pass, RowRef row, String from, String to,
            String action, String reasonCode, Map<String, Object> values, String reasoning) {
        Map<String, Object> f = fields("row", row.id(), "month", row.month(), "position", row.positionId(),
                "symbol", row.symbol(), "from", from, "to", to, "run", runId, "pass", pass);
        f.putAll(values);
        log.info("savings-plan transition {}", kv(f));
        insert(runId, row.symbol(), action, reasonCode, orderJson(row, f), null, reasoning);
    }

    /** A position-level skip: the {@code savings-plan skip} line with the compared values, then the
     *  ∅→SKIPPED transition. */
    public void skip(String runId, String pass, RowRef row, String reason, String detail,
            Map<String, Object> values) {
        Map<String, Object> f = fields("month", row.month(), "position", row.positionId(),
                "symbol", row.symbol(), "reason", reason, "detail", detail, "run", runId, "pass", pass);
        log.info("savings-plan skip {}", kv(f));
        Map<String, Object> v = fields("reason", reason, "detail", detail);
        v.putAll(values);
        transition(runId, pass, row, "none", SavingsBuy.SKIPPED, "SAVINGS_SKIP", reason, v, detail);
    }

    /** A stage-level skip (NO_ACCOUNT, FX_MISSING, CAP_FULL): a decision row only, never a buy row. */
    public void stageSkip(String runId, String pass, String month, String reason, String detail) {
        log.info("savings-plan skip scope=stage month={} reason={} detail={} run={} pass={}",
                month, reason, plain(detail), runId, pass);
        ObjectNode oj = mapper.createObjectNode();
        oj.put("month", month);
        oj.put("scope", "stage");
        oj.put("detail", detail);
        insert(runId, null, "SAVINGS_SKIP", reason, oj, null, detail);
    }

    /** A parallel pass already owns {@code (month, position)} — detected via a null insert id
     *  (CAS / ON CONFLICT DO NOTHING). INFO only, never a decision row: the row the OTHER pass wrote
     *  already carries the full trail, and this pass folds the occurrence into its single
     *  {@code savings-plan stage} line's {@code raced} counter rather than emitting a second one. */
    public void raced(String runId, String pass, String month, long positionId, String symbol) {
        log.info("savings-plan skip scope=race month={} position={} symbol={} run={} pass={}",
                month, positionId, symbol, runId, pass);
    }

    /** Exactly one per stage per pass — also (especially) when the stage did nothing. */
    public void stage(String stage, String runId, String pass, String outcome, String why,
            Map<String, Object> counts) {
        log.info("savings-plan stage stage={} run={} pass={} outcome={} why={} {}", stage, runId, pass,
                outcome, plain(why), kv(counts));
    }

    /** WARN (severity WARN) / ERROR (severity CRITICAL) line, ESCALATE row, best-effort Telegram. */
    public void escalate(String runId, String pass, RowRef row, String symbol, String code,
            String severity, Map<String, Object> values, String reasoning) {
        Map<String, Object> f = fields("code", code, "severity", severity,
                "row", row == null ? null : row.id(), "position", row == null ? null : row.positionId(),
                "symbol", symbol, "run", runId, "pass", pass);
        f.putAll(values);
        if ("CRITICAL".equals(severity)) {
            log.error("savings-plan escalation {} — {}", kv(f), reasoning);
        } else {
            log.warn("savings-plan escalation {} — {}", kv(f), reasoning);
        }
        ObjectNode inputs = mapper.createObjectNode();
        inputs.put("severity", severity);
        insert(runId, symbol, "ESCALATE", code, orderJson(row, f), inputs, reasoning);
        try {
            telegram.notifyAlert(symbol == null ? "SPARPLAN" : symbol, code, severity, reasoning);
        } catch (RuntimeException e) {
            log.info("savings-plan telegram alert {} not sent: {}", code, e.getMessage());
        }
    }

    /** A non-transition decision row (TRIM rows of the window stop). */
    public void record(String runId, String symbol, String action, String reasonCode,
            Map<String, Object> orderValues, String reasoning) {
        ObjectNode oj = mapper.createObjectNode();
        orderValues.forEach((k, v) -> put(oj, k, v));
        insert(runId, symbol, action, reasonCode, oj, null, reasoning);
    }

    /** One line before and one after every broker call of the savings stages. Rethrows unchanged. */
    public <T> T broker(String stage, String runId, String pass, String op, String args,
            Supplier<T> call, Function<T, String> describe) {
        log.info("savings-plan broker stage={} run={} pass={} op={} phase=intent {}", stage, runId, pass, op, args);
        try {
            T out = call.get();
            log.info("savings-plan broker stage={} run={} pass={} op={} phase=result result=accepted {}",
                    stage, runId, pass, op, describe.apply(out));
            return out;
        } catch (BrokerRejectedException e) {
            log.info("savings-plan broker stage={} run={} pass={} op={} phase=result result=rejected code={} text={}",
                    stage, runId, pass, op, e.rejectCode(), plain(e.getMessage()));
            throw e;
        } catch (BrokerUnavailableException e) {
            log.info("savings-plan broker stage={} run={} pass={} op={} phase=result result=indeterminate text={}",
                    stage, runId, pass, op, plain(e.getMessage()));
            throw e;
        } catch (RuntimeException e) {
            // Anything else (e.g. a mapping error) is NOT a broker verdict — still a phase=result
            // line so no broker call is ever silent, then rethrown unchanged.
            log.info("savings-plan broker stage={} run={} pass={} op={} phase=result result=error text={}",
                    stage, runId, pass, op, plain(e.getMessage()));
            throw e;
        }
    }

    public void brokerRun(String stage, String runId, String pass, String op, String args, Runnable call) {
        broker(stage, runId, pass, op, args, () -> { call.run(); return Boolean.TRUE; }, x -> "");
    }

    public int countFor(String code, long buyId) {
        return decisionRepo.countByReasonCodeForSavingsBuy(code, buyId);
    }

    public boolean escalatedFor(String code, long buyId) {
        return countFor(code, buyId) > 0;
    }

    public void digest(String text) {
        try {
            telegram.notifyDigest(text);
        } catch (RuntimeException e) {
            log.info("savings-plan telegram digest not sent: {}", e.getMessage());
        }
    }

    private ObjectNode orderJson(RowRef row, Map<String, Object> f) {
        ObjectNode oj = mapper.createObjectNode();
        if (row != null) {
            if (row.id() != null) oj.put("savings_buy_id", row.id());
            oj.put("position_id", row.positionId());
            oj.put("month", row.month());
        }
        f.forEach((k, v) -> {
            if (!"row".equals(k) && !"position".equals(k)) put(oj, k, v);
        });
        return oj;
    }

    private static void put(ObjectNode oj, String k, Object v) {
        if (v == null) oj.putNull(k);
        else if (v instanceof BigDecimal b) oj.put(k, b);
        else if (v instanceof Integer i) oj.put(k, i);
        else if (v instanceof Long l) oj.put(k, l);
        else if (v instanceof Boolean b) oj.put(k, b);
        else oj.put(k, String.valueOf(v));
    }

    private void insert(String runId, String symbol, String action, String reasonCode,
            ObjectNode orderJson, ObjectNode inputs, String reasoning) {
        decisionRepo.insert(new DecisionLog(null, runId, ruleVersions.active(), TRIGGER, null, null,
                null, symbol, inputs, null, action, reasonCode, orderJson, reasoning, null, null, null));
    }
}
