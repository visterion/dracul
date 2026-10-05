package de.visterion.dracul.strigoi.momentum;

import de.visterion.dracul.executor.ExecutorPosition;
import de.visterion.dracul.executor.ExecutorPositionRepository;
import de.visterion.dracul.executor.ExecutorSignalRepository;
import de.visterion.dracul.hunting.DataSourceResult;
import de.visterion.dracul.hunting.agora.AgoraCompanyData;
import de.visterion.dracul.hunting.agora.AgoraIndexConstituents;
import de.visterion.dracul.hunting.agora.IndexConstituent;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import de.visterion.dracul.notify.TelegramNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code fetch_momentum_ranking} tool (spec 2026-10-04 §5.2/§5.3/§5.5). Per Vistierie run:
 * (1) a stored snapshot is returned unchanged; (2) the calendar decides whether a rebalance is
 * due — not due answers before any Agora call and stores a minimal snapshot; (3) a due run ranks
 * the S&P 500 (universe-min, chunks of 100, completeness floor) and stores the full snapshot the
 * completion reads. MISSED is raised on weekday catch-up+1.
 */
@Service
@ConditionalOnProperty(value = "dracul.strigoi.momentum.enabled", havingValue = "true")
public class MomentumRankingService {

    private static final Logger log = LoggerFactory.getLogger(MomentumRankingService.class);
    private static final String SOURCE = "agora";

    private final MomentumSettings settings;
    private final MomentumRepository repo;
    private final AgoraIndexConstituents index;
    private final MomentumBarsClient bars;
    private final AgoraCompanyData company;
    private final ObjectProvider<ExecutorPositionRepository> positions;
    private final ObjectProvider<ExecutorSignalRepository> signals;
    private final TelegramNotifier telegram;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public MomentumRankingService(MomentumSettings settings, MomentumRepository repo,
            AgoraIndexConstituents index, MomentumBarsClient bars, AgoraCompanyData company,
            ObjectProvider<ExecutorPositionRepository> positions,
            ObjectProvider<ExecutorSignalRepository> signals, TelegramNotifier telegram,
            ObjectMapper mapper) {
        this(settings, repo, index, bars, company, positions, signals, telegram, mapper,
                Clock.systemUTC());
    }

    MomentumRankingService(MomentumSettings settings, MomentumRepository repo,
            AgoraIndexConstituents index, MomentumBarsClient bars, AgoraCompanyData company,
            ObjectProvider<ExecutorPositionRepository> positions,
            ObjectProvider<ExecutorSignalRepository> signals, TelegramNotifier telegram,
            ObjectMapper mapper, Clock clock) {
        this.settings = settings;
        this.repo = repo;
        this.index = index;
        this.bars = bars;
        this.company = company;
        this.positions = positions;
        this.signals = signals;
        this.telegram = telegram;
        this.mapper = mapper;
        this.clock = clock;
    }

    public Map<String, Object> rank(String runId) {
        Optional<MomentumRepository.StoredSnapshot> stored = repo.findSnapshot(runId);
        if (stored.isPresent()) {
            log.info("strigoi-momentum run {}: ranking snapshot already stored — returned unchanged", runId);
            return llmView(stored.get().payload());
        }
        LocalDate today = LocalDate.now(clock);
        YearMonth start = repo.startMonth(YearMonth.from(today));
        MomentumCalendar.Decision decision = MomentumCalendar.decide(today, start,
                settings.catchUpWeekdays(), repo::rebalanceCompleted);
        Optional<YearMonth> missed = MomentumCalendar.missedMonth(today, start,
                settings.catchUpWeekdays(), repo::rebalanceCompleted);
        missed.ifPresent(m -> alertMissed(m, today, runId));
        if (!decision.due()) {
            Map<String, Object> ranking = new LinkedHashMap<>();
            ranking.put("rebalance_due", false);
            ranking.put("today", today.toString());
            ranking.put("target_month", decision.target() == null ? null : decision.target().toString());
            ranking.put("reason", decision.reason());
            missed.ifPresent(m -> ranking.put("rebalance_missed", m.toString()));
            log.info("strigoi-momentum run {}: no rebalance due — {}", runId, decision.reason());
            return persist(runId, null, null, false, MomentumSnapshot.NOT_DUE,
                    output(ranking, health("healthy", "dracul", null, false)), Map.of());
        }
        return rankDue(runId, decision.target(), missed.orElse(null));
    }

    private Map<String, Object> rankDue(String runId, YearMonth target, YearMonth missed) {
        DataSourceResult<IndexConstituent> idx = index.constituents(MomentumSettings.INDEX);
        if (!idx.health().isHealthy()) {
            return unavailable(runId, target, "index " + MomentumSettings.INDEX + ": " + idx.health().detail());
        }
        if (idx.items().size() < settings.universeMin()) {
            return unavailable(runId, target, "universe truncated: " + idx.items().size()
                    + " constituents < universe-min " + settings.universeMin());
        }
        Map<String, IndexConstituent> universe = new LinkedHashMap<>();
        int excluded = 0;
        for (IndexConstituent c : idx.items()) {
            String symbol = MomentumBook.norm(c.symbol());
            if (settings.excludeSymbols().contains(symbol)) {
                excluded++;
                continue;
            }
            universe.putIfAbsent(symbol, c);
        }
        List<String> symbols = List.copyOf(universe.keySet());

        Map<String, MomentumBarsClient.Series> data = new HashMap<>();
        int chunk = 0;
        int failedChunks = 0;
        for (int from = 0; from < symbols.size(); from += MomentumSettings.CHUNK_SIZE) {
            List<String> part = symbols.subList(from, Math.min(from + MomentumSettings.CHUNK_SIZE, symbols.size()));
            chunk++;
            try {
                data.putAll(bars.fetch(part, settings.rocPeriod(), MomentumSettings.SERIES,
                        MomentumSettings.FETCH_DAYS));
            } catch (AgoraUnavailableException e) {
                if (e.scope() == AgoraUnavailableException.Scope.SOURCE) {
                    return unavailable(runId, target, "source-scope outage at get_indicators_batch chunk "
                            + chunk + " — nothing ranked: " + e.getMessage());
                }
                failedChunks++;
                log.warn("strigoi-momentum run {}: get_indicators_batch chunk {} failed for this request "
                        + "only — its {} symbol(s) count as missing", runId, chunk, part.size());
            }
        }
        MomentumRanker.Ranking ranking = MomentumRanker.rank(symbols, data, settings);
        int ranked = ranking.ranked().size();
        BigDecimal share = symbols.isEmpty() ? BigDecimal.ZERO
                : BigDecimal.valueOf(ranked).divide(BigDecimal.valueOf(symbols.size()), 4, RoundingMode.HALF_UP);
        if (share.compareTo(settings.completenessFloor()) < 0) {
            return unavailable(runId, target, "ranked " + ranked + " of " + symbols.size()
                    + " (< completeness-floor " + settings.completenessFloor().toPlainString() + "; "
                    + countsText(ranking, failedChunks) + ")");
        }

        MomentumBook book = MomentumBook.read(positions.getIfAvailable(), signals.getIfAvailable(),
                settings.executorConnection());
        int offerN = Math.min(settings.topN() + settings.refillN(), ranked);
        int marketCapMissing = 0;
        List<Map<String, Object>> offered = new ArrayList<>();
        for (MomentumRanker.Ranked r : ranking.ranked().subList(0, offerN)) {
            IndexConstituent c = universe.get(r.symbol());
            BigDecimal cap = marketCap(r.symbol());
            if (cap == null) marketCapMissing++;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank", r.rank());
            m.put("symbol", r.symbol());
            m.put("company_name", c.companyName());
            m.put("sector", c.sector());
            m.put("momentum_12_1_pct", scale2(r.momentumPct()));
            m.put("last_close", r.lastClose());
            m.put("return_1m_pct", r.return1mPct());
            m.put("market_cap_millions", cap);
            m.put("held_momentum", book.heldMomentum(r.symbol()));
            m.put("held_elsewhere", book.heldElsewhere().contains(r.symbol()));
            m.put("pending_signal", book.pendingAny().contains(r.symbol()));
            m.put("possible_corporate_action", r.possibleCorporateAction());
            m.put("worst_1d_return_pct", r.worst1dPct());
            offered.add(m);
        }
        List<Map<String, Object>> held = new ArrayList<>();
        for (ExecutorPosition p : book.momentumOpen()) {
            String symbol = MomentumBook.norm(p.symbol());
            Optional<MomentumRanker.Ranked> r = ranking.find(symbol);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol", symbol);
            m.put("entry_filled", p.entryFilledAt() != null);
            m.put("rebalance_exit_pending", p.rebalanceExitAt() != null);
            m.put("rank", r.map(MomentumRanker.Ranked::rank).orElse(null));
            if (!universe.containsKey(symbol)) {
                m.put("status", "not_in_universe");
            } else if (r.isPresent()) {
                m.put("status", "ranked");
            } else {
                m.put("status", "unranked");
                m.put("unranked_reason", ranking.unranked().get(symbol));
            }
            held.add(m);
        }
        List<Map<String, Object>> suspects = new ArrayList<>();
        for (MomentumRanker.Suspect s : ranking.suspects()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol", s.symbol());
            m.put("worst_1d_return_pct", s.worst1dPct());
            m.put("best_1d_return_pct", s.best1dPct());
            suspects.add(m);
        }
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("excluded", excluded);
        counts.put(MomentumRanker.MISSING, ranking.count(MomentumRanker.MISSING));
        counts.put(MomentumRanker.TOO_FEW_BARS, ranking.count(MomentumRanker.TOO_FEW_BARS));
        counts.put(MomentumRanker.BELOW_MIN_PRICE, ranking.count(MomentumRanker.BELOW_MIN_PRICE));
        counts.put(MomentumRanker.DATA_SUSPECT, ranking.count(MomentumRanker.DATA_SUSPECT));
        counts.put("failed_chunks", failedChunks);
        counts.put("market_cap_unavailable", marketCapMissing);

        int topEnd = Math.min(settings.topN(), offered.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rebalance_due", true);
        out.put("target_month", target.toString());
        out.put("as_of_bar_date", ranking.asOf() == null ? null : ranking.asOf().toString());
        out.put("universe_size", symbols.size());
        out.put("ranked_count", ranked);
        out.put("top", List.copyOf(offered.subList(0, topEnd)));
        out.put("refill", List.copyOf(offered.subList(topEnd, offered.size())));
        out.put("held", held);
        out.put("suspects", suspects);
        out.put("counts", counts);
        if (missed != null) out.put("rebalance_missed", missed.toString());

        boolean partial = failedChunks > 0 || ranking.count(MomentumRanker.MISSING) > 0;
        String detail = partial
                ? "ranked " + ranked + " of " + symbols.size() + "; " + countsText(ranking, failedChunks)
                : null;
        if (partial) log.warn("strigoi-momentum run {}: ranking partial — {}", runId, detail);

        Map<String, Object> extras = new LinkedHashMap<>();
        List<Map<String, Object>> rankedAll = new ArrayList<>();
        for (MomentumRanker.Ranked r : ranking.ranked()) {
            rankedAll.add(Map.of("symbol", r.symbol(), "rank", r.rank(), "momentum_pct", scale2(r.momentumPct())));
        }
        extras.put(MomentumSnapshot.RANKED_ALL, rankedAll);
        extras.put(MomentumSnapshot.UNIVERSE, symbols);
        extras.put(MomentumSnapshot.UNRANKED, ranking.unranked());
        extras.put(MomentumSnapshot.OFFERED, offered);
        extras.put(MomentumSnapshot.RANKED_COUNT, ranked);
        log.info("strigoi-momentum run {}: ranked {} of {} for {} (suspects {}, missing {}, failed chunks {})",
                runId, ranked, symbols.size(), target, ranking.suspects().size(),
                ranking.count(MomentumRanker.MISSING), failedChunks);
        return persist(runId, ranking.asOf(), target, true,
                partial ? MomentumSnapshot.PARTIAL : MomentumSnapshot.HEALTHY,
                output(out, health("healthy", SOURCE, detail, partial)), extras);
    }

    private Map<String, Object> unavailable(String runId, YearMonth target, String detail) {
        log.warn("strigoi-momentum run {}: ranking unavailable for {} — {}; no exits and no entries "
                + "from this run", runId, target, detail);
        Map<String, Object> ranking = new LinkedHashMap<>();
        ranking.put("rebalance_due", true);
        ranking.put("target_month", target.toString());
        ranking.put("unavailable", detail);
        return persist(runId, null, target, true, MomentumSnapshot.UNAVAILABLE,
                output(ranking, health("unavailable", SOURCE, detail, false)), Map.of());
    }

    /** Stores the snapshot once; a concurrent call of the same run that won the insert decides. */
    private Map<String, Object> persist(String runId, LocalDate asOf, YearMonth month, boolean due,
            String health, Map<String, Object> llmOutput, Map<String, Object> extras) {
        ObjectNode payload = mapper.createObjectNode();
        payload.set(MomentumSnapshot.LLM, mapper.valueToTree(llmOutput));
        extras.forEach((k, v) -> payload.set(k, mapper.valueToTree(v)));
        if (repo.insertSnapshot(runId, asOf, month, due, health, payload)) return llmOutput;
        return repo.findSnapshot(runId).map(s -> llmView(s.payload()))
                .orElseThrow(() -> new IllegalStateException(
                        "momentum snapshot for run " + runId + " neither inserted nor found"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> llmView(JsonNode payload) {
        return mapper.convertValue(payload.path(MomentumSnapshot.LLM), Map.class);
    }

    private static Map<String, Object> output(Map<String, Object> ranking, Map<String, Object> health) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ranking", ranking);
        out.put("data_source_health", health);
        return out;
    }

    private static Map<String, Object> health(String status, String source, String detail, boolean partial) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("status", status);
        h.put("source", source);
        h.put("detail", detail);
        h.put("checked_at", Instant.now().toString());
        if (partial) h.put("partial", true);
        return h;
    }

    private BigDecimal marketCap(String symbol) {
        JsonNode p = company.profile(symbol);
        if (p == null) return null;
        JsonNode v = p.path("marketCapitalization");
        if (!v.isNumber()) return null;
        return v.decimalValue().setScale(0, RoundingMode.HALF_UP);
    }

    private void alertMissed(YearMonth month, LocalDate today, String runId) {
        String text = "momentum rebalance for " + month + " MISSED: no completed rebalance by weekday "
                + (settings.catchUpWeekdays() + 1) + " of " + YearMonth.from(today) + " (run " + runId
                + ") — the book keeps last month's names; check the strigoi-momentum runs and "
                + "momentum_ranking_snapshot";
        log.error("MOMENTUM_REBALANCE_MISSED: {}", text);
        try {
            telegram.notifyAlert("MOMENTUM", "MOMENTUM_REBALANCE_MISSED", "CRITICAL", text);
        } catch (RuntimeException e) {
            log.warn("strigoi-momentum: MISSED alert could not be sent: {}", e.toString());
        }
    }

    private static String countsText(MomentumRanker.Ranking r, int failedChunks) {
        return "missing " + r.count(MomentumRanker.MISSING) + ", too_few_bars "
                + r.count(MomentumRanker.TOO_FEW_BARS) + ", below_min_price "
                + r.count(MomentumRanker.BELOW_MIN_PRICE) + ", data_suspect "
                + r.count(MomentumRanker.DATA_SUSPECT) + ", failed chunks " + failedChunks;
    }

    private static BigDecimal scale2(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }
}
