package de.visterion.dracul.strigoi.momentum;

import de.visterion.dracul.executor.ExecutorPosition;
import de.visterion.dracul.executor.ExecutorPositionRepository;
import de.visterion.dracul.executor.ExecutorSignalRepository;
import de.visterion.dracul.executor.ExitProfile;
import de.visterion.dracul.prey.Prey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The strigoi-momentum completion (spec 2026-10-04 §4, §5.3). Runs entirely inside
 * {@code selectForPersist} (HuntController swallows exceptions of {@code onCompletionAccepted},
 * so nothing that gates the month may live there), in this order: (1) status — checked by the
 * base; (2) the run's stored snapshot, the ONLY ranking input; (3) vetoes validated against the
 * offered names; (4) final Top N = rank order minus vetoes, skipping names held or pending under
 * another profile, refilled only from the offered names; (5) rebalance flags and flag clearing;
 * (6) entries = final minus MOMENTUM-held minus MOMENTUM-pending, as code-built prey; (7) the
 * target month is marked — only after (5) succeeded; (8) health notes. Every step is idempotent,
 * so a re-delivered completion re-runs them without effect (prey insert dedups on the day).
 */
@Service
@ConditionalOnProperty(value = "dracul.strigoi.momentum.enabled", havingValue = "true")
public class MomentumCompletionService {

    private static final Logger log = LoggerFactory.getLogger(MomentumCompletionService.class);
    private static final String AGENT = MomentumSettings.AGENT;

    static final List<String> NOTE_KEYS = List.of("vetoed", "veto_invalid", "refilled",
            "held_elsewhere", "short_book", "rebalance_exits", "flags_cleared", "held_unranked",
            "carried_too_long", "not_in_universe", "suspect_excluded", "llm_prey_ignored",
            "no_snapshot", "executor_disabled", "rebalance_exit_failed", "clear_too_late");
    /** Notes that are anomalies (WARN); the rest are a normal month's activity (INFO). */
    static final Set<String> WARN_KEYS = Set.of("veto_invalid", "short_book", "held_unranked",
            "carried_too_long", "not_in_universe", "llm_prey_ignored", "no_snapshot",
            "executor_disabled", "rebalance_exit_failed", "clear_too_late");
    static final List<String> RISKS = List.of("momentum crash", "data: spin-off artefacts possible");
    static final List<String> KILL_CRITERIA = List.of(
            "managed by exit profile MOMENTUM: monthly rebalance exit or emergency stop");
    /** A held name unranked in this many consecutive due snapshots (current included) is exited. */
    static final int CARRY_LIMIT = 3;

    public record Result(List<Prey> prey, Map<String, Integer> notes) {}

    private final MomentumSettings settings;
    private final MomentumRepository repo;
    private final ObjectProvider<ExecutorPositionRepository> positions;
    private final ObjectProvider<ExecutorSignalRepository> signals;
    private final Clock clock;

    @Autowired
    public MomentumCompletionService(MomentumSettings settings, MomentumRepository repo,
            ObjectProvider<ExecutorPositionRepository> positions,
            ObjectProvider<ExecutorSignalRepository> signals) {
        this(settings, repo, positions, signals, Clock.systemUTC());
    }

    MomentumCompletionService(MomentumSettings settings, MomentumRepository repo,
            ObjectProvider<ExecutorPositionRepository> positions,
            ObjectProvider<ExecutorSignalRepository> signals, Clock clock) {
        this.settings = settings;
        this.repo = repo;
        this.positions = positions;
        this.signals = signals;
        this.clock = clock;
    }

    public Result complete(JsonNode output, String runId, int llmPreyCount) {
        Map<String, Integer> notes = new LinkedHashMap<>();
        NOTE_KEYS.forEach(k -> notes.put(k, 0));
        notes.put("llm_prey_ignored", llmPreyCount);
        try {
            return new Result(run(output, runId, notes), notes);
        } finally {
            publish(runId, notes);
        }
    }

    private List<Prey> run(JsonNode output, String runId, Map<String, Integer> notes) {
        ExecutorPositionRepository pos = positions.getIfAvailable();
        ExecutorSignalRepository sig = signals.getIfAvailable();
        if (pos == null || sig == null) {
            notes.put("executor_disabled", 1);
            log.warn("{} run {}: executor disabled — no exits, no entries, the month stays open", AGENT, runId);
            return List.of();
        }
        // 2. The stored snapshot is the only ranking input — never recomputed, the LLM's echoed
        //    health is never trusted.
        Optional<MomentumRepository.StoredSnapshot> stored =
                runId == null ? Optional.empty() : repo.findSnapshot(runId);
        if (stored.isEmpty()) {
            notes.put("no_snapshot", 1);
            log.warn("{} run {}: no ranking snapshot for this run (tool not called, or no run id) — "
                    + "completion is a no-op", AGENT, runId);
            return List.of();
        }
        MomentumSnapshot snap = MomentumSnapshot.of(stored.get());
        if (!snap.rebalanceDue()) {
            log.info("{} run {}: no rebalance due — completion is a no-op", AGENT, runId);
            return List.of();
        }
        if (!snap.usable()) {
            log.warn("{} run {}: ranking {} for {} — no exits, no entries, the month stays open",
                    AGENT, runId, snap.health(), snap.month());
            return List.of();
        }
        notes.put("suspect_excluded", (int) snap.unranked().values().stream()
                .filter(MomentumRanker.DATA_SUSPECT::equals).count());

        // 3. Vetoes: only the offered names, a reason required.
        Map<String, MomentumSnapshot.Offered> offered = new LinkedHashMap<>();
        for (MomentumSnapshot.Offered o : snap.offered()) offered.put(o.symbol(), o);
        Set<String> vetoed = validVetoes(output.path("vetoes"), offered.keySet(), notes, runId);

        // 4. Final Top N in rank order: vetoed and held-elsewhere names are skipped, the offered
        //    refill names move up. Fewer than N left -> the book holds fewer (short_book).
        MomentumBook book = MomentumBook.read(pos, sig, settings.executorConnection());
        List<MomentumSnapshot.Offered> fin = new ArrayList<>();
        for (MomentumSnapshot.Offered o : offered.values()) {
            if (fin.size() >= settings.topN()) break;
            if (vetoed.contains(o.symbol())) continue;
            // A name this book already holds as MOMENTUM is never "held elsewhere": a stray
            // pending signal of another hunter (REDUNDANCY-vetoed anyway) must not push our own
            // holding out of the final list and into a rebalance exit.
            if (!book.heldMomentum(o.symbol()) && book.heldElsewhere().contains(o.symbol())) {
                notes.merge("held_elsewhere", 1, Integer::sum);
                continue;
            }
            fin.add(o);
        }
        notes.put("refilled", (int) fin.stream().filter(o -> o.rank() > settings.topN()).count());
        notes.put("short_book", Math.max(0, settings.topN() - fin.size()));

        // 5. Rebalance flags. A failure leaves the month open for the next weekday.
        Set<String> finalSymbols = new HashSet<>();
        fin.forEach(o -> finalSymbols.add(o.symbol()));
        try {
            applyRebalanceFlags(pos, book, snap, finalSymbols, notes, runId);
        } catch (RuntimeException e) {
            notes.put("rebalance_exit_failed", 1);
            log.warn("{} run {}: writing the rebalance flags failed — no entries, month {} stays open "
                    + "for the next weekday: {}", AGENT, runId, snap.month(), e.toString(), e);
            return List.of();
        }

        // 6. Entries: code-built prey for final names this book neither holds nor has pending.
        List<Prey> prey = new ArrayList<>();
        String discoveredAt = Instant.now(clock).toString();
        for (MomentumSnapshot.Offered o : fin) {
            if (book.heldMomentum(o.symbol()) || book.pendingMomentum().contains(o.symbol())) continue;
            prey.add(prey(o, snap.rankedCount(), discoveredAt));
        }

        // 7. The month is done — only now (after step 5).
        boolean marked = repo.markRebalanced(snap.month(), runId);
        log.info("{} run {}: rebalance {} — final {} name(s), {} new entr(y/ies), month {}", AGENT,
                runId, snap.month(), fin.size(), prey.size(), marked ? "marked" : "already marked");
        return List.copyOf(prey);
    }

    private void applyRebalanceFlags(ExecutorPositionRepository pos, MomentumBook book,
            MomentumSnapshot snap, Set<String> finalSymbols, Map<String, Integer> notes, String runId) {
        String conn = settings.executorConnection();
        Instant now = clock.instant();
        List<JsonNode> recent = null;
        for (ExecutorPosition p : book.momentumOpen()) {
            String symbol = MomentumBook.norm(p.symbol());
            if (finalSymbols.contains(symbol)) {
                // Back in (or still in) the final Top N: a flag from an earlier month whose
                // flatten kept failing must not sell a re-ranked name.
                if (p.rebalanceExitAt() != null) {
                    boolean cleared = pos.clearRebalanceExit(p.id(), conn);
                    if (p.pendingExitReason() != null) {
                        // The flatten is already submitted; clearing the flag cannot recall it.
                        notes.merge("clear_too_late", 1, Integer::sum);
                        log.warn("{} run {}: {} is back in the final Top {} but its flatten is "
                                + "already submitted ({}) — re-ranked name will still be sold",
                                AGENT, runId, symbol, settings.topN(), p.pendingExitReason());
                    } else if (cleared) {
                        notes.merge("flags_cleared", 1, Integer::sum);
                    }
                }
                continue;
            }
            if (p.entryFilledAt() == null) continue;   // unfilled entry: left to its GTD expiry
            if (!snap.inUniverse(symbol)) {
                notes.merge("not_in_universe", 1, Integer::sum);
                flag(pos, p, conn, now, notes, runId, "left the index");
                continue;
            }
            if (snap.rankOf(symbol) != null) {
                flag(pos, p, conn, now, notes, runId, "rank " + snap.rankOf(symbol) + " outside the final Top "
                        + settings.topN());
                continue;
            }
            notes.merge("held_unranked", 1, Integer::sum);
            if (recent == null) recent = repo.lastDueSnapshotPayloads(snap.month(), CARRY_LIMIT);
            long unrankedRuns = recent.stream()
                    .filter(payload -> MomentumSnapshot.unrankedSymbols(payload).contains(symbol)).count();
            if (unrankedRuns >= CARRY_LIMIT) {
                notes.merge("carried_too_long", 1, Integer::sum);
                flag(pos, p, conn, now, notes, runId, "unranked for " + CARRY_LIMIT + " rebalances");
            } else {
                log.warn("{} run {}: held {} has no valid rank ({}) — carried, not exited ({} of {} "
                        + "rebalances)", AGENT, runId, symbol, snap.unranked().get(symbol),
                        unrankedRuns, CARRY_LIMIT);
            }
        }
    }

    private void flag(ExecutorPositionRepository pos, ExecutorPosition p, String conn, Instant now,
            Map<String, Integer> notes, String runId, String why) {
        notes.merge("rebalance_exits", 1, Integer::sum);
        boolean newly = pos.markRebalanceExit(p.id(), conn, now);
        log.info("{} run {}: {} committed to a rebalance exit ({}){}", AGENT, runId, p.symbol(), why,
                newly ? "" : " — already flagged");
    }

    private Set<String> validVetoes(JsonNode vetoes, Set<String> offered, Map<String, Integer> notes,
            String runId) {
        Set<String> out = new LinkedHashSet<>();
        if (vetoes.isArray()) {
            for (JsonNode v : vetoes) {
                String symbol = MomentumBook.norm(v.path("symbol").asString(""));
                String reason = v.path("reason").asString("").trim();
                if (symbol.isEmpty() || reason.isEmpty() || !offered.contains(symbol)) {
                    notes.merge("veto_invalid", 1, Integer::sum);
                    log.warn("{} run {}: veto dropped — the symbol must be one of the offered names and "
                            + "a reason is required: {}", AGENT, runId, v);
                    continue;
                }
                if (out.add(symbol)) log.info("{} run {}: {} vetoed — {}", AGENT, runId, symbol, reason);
            }
        }
        notes.put("vetoed", out.size());
        return out;
    }

    private Prey prey(MomentumSnapshot.Offered o, int rankedCount, String discoveredAt) {
        String momentum = o.momentumPct() == null ? "n/a" : signed(o.momentumPct());
        String thesis = "12-1 momentum rank " + o.rank() + " of " + rankedCount + ": " + momentum
                + " % (t−" + settings.lookbackDays() + "..t−" + settings.skipDays() + ")";
        List<String> signals = List.of("rank " + o.rank() + " of " + rankedCount,
                "momentum_12_1 " + momentum + " %",
                "return_1m " + (o.return1mPct() == null ? "n/a" : signed(o.return1mPct()) + " %"));
        String company = o.companyName() == null || o.companyName().isBlank() ? o.symbol() : o.companyName();
        return new Prey(UUID.randomUUID().toString(), o.symbol(), company, ExitProfile.MOMENTUM_12_1,
                MomentumSettings.PREY_CONFIDENCE, thesis, signals, RISKS, KILL_CRITERIA,
                MomentumSettings.HORIZON, AGENT, discoveredAt, null);
    }

    /** One decimal with an explicit sign: +85.3 / -4.4. */
    static String signed(BigDecimal v) {
        BigDecimal r = v.setScale(1, RoundingMode.HALF_UP);
        return (r.signum() >= 0 ? "+" : "") + r.toPlainString();
    }

    private void publish(String runId, Map<String, Integer> notes) {
        StringBuilder sb = new StringBuilder("run=").append(runId == null ? "-" : runId);
        boolean anomaly = false;
        for (String key : NOTE_KEYS) {
            int v = notes.getOrDefault(key, 0);
            sb.append(' ').append(key).append('=').append(v);
            anomaly |= v > 0 && WARN_KEYS.contains(key);
        }
        if (anomaly) log.warn("strigoi-momentum completion notes: {}", sb);
        else log.info("strigoi-momentum completion notes: {}", sb);
    }
}
