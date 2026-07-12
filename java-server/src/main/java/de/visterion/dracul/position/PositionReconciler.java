package de.visterion.dracul.position;

import de.visterion.dracul.depot.AgoraDepotClient;
import de.visterion.dracul.depot.DepotPosition;
import de.visterion.dracul.depot.DepotUnavailableException;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.verdict.VerdictRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Keeps {@code position_context} (V28) in sync with the live depot: backfills a context row
 * (verdict-linked when a matching verdict exists, minimal otherwise) for depot positions that
 * have none yet, and closes context rows whose symbol is no longer held. This is what makes
 * the depot -- not the research pipeline -- authoritative for "what's currently open" (the
 * "depot as single source of truth" goal).
 *
 * <p>Fail-soft throughout, mirroring {@link de.visterion.dracul.stopguard.StopProximityWatcher}:
 * a {@link DepotUnavailableException} makes the whole pass a no-op (no throw, WARN once), and
 * a single symbol's verdict-lookup/backfill/close failure is skipped rather than aborting the
 * rest of the pass.
 */
@Component
@ConditionalOnProperty(value = "dracul.position.enabled", matchIfMissing = true)
public class PositionReconciler {

    private static final Logger log = LoggerFactory.getLogger(PositionReconciler.class);

    private final AgoraDepotClient depotClient;
    private final PositionContextRepository contextRepo;
    private final VerdictRepository verdictRepo;
    private final PreyRepository preyRepo;
    private final ObjectMapper mapper;
    private final String connection;

    public PositionReconciler(AgoraDepotClient depotClient, PositionContextRepository contextRepo,
            VerdictRepository verdictRepo, PreyRepository preyRepo, ObjectMapper mapper,
            @Value("${dracul.position.connection:depot-1}") String connection) {
        this.depotClient = depotClient;
        this.contextRepo = contextRepo;
        this.verdictRepo = verdictRepo;
        this.preyRepo = preyRepo;
        this.mapper = mapper;
        this.connection = connection;
    }

    @Scheduled(cron = "${dracul.position.reconcile-cron:0 0 12 * * *}")
    public void reconcile() {
        List<DepotPosition> positions;
        try {
            positions = depotClient.positions(connection).positions();
        } catch (DepotUnavailableException e) {
            log.warn("position reconcile: depot unavailable for connection {} -- skipping pass: {}",
                    connection, e.toString());
            return;
        }

        Set<String> depotSymbols = new LinkedHashSet<>();
        for (DepotPosition p : positions) {
            if (p.symbol() != null) depotSymbols.add(p.symbol());
        }

        backfill(depotSymbols);
        close(depotSymbols);
    }

    /** Opens a context row for every depot symbol that doesn't already have one. */
    private void backfill(Set<String> depotSymbols) {
        for (String symbol : depotSymbols) {
            try {
                if (contextRepo.findOpenBySymbol(connection, symbol).isPresent()) continue;

                var verdict = verdictRepo.findLatestBySymbol(symbol);
                if (verdict.isEmpty()) {
                    contextRepo.upsertOnOpen(connection, symbol, null, null, null, null, null, "none");
                    continue;
                }

                var v = verdict.get();
                JsonNode killCriteria = resolveKillCriteria(v.id());
                JsonNode thesisSnapshot = thesisSnapshot(v);
                contextRepo.upsertOnOpen(connection, symbol, v.id(), killCriteria, v.horizon(),
                        thesisSnapshot, null, "reconcile");
            } catch (RuntimeException e) {
                log.warn("position reconcile: backfill failed for symbol {} -- skipping: {}",
                        symbol, e.getMessage());
            }
        }
    }

    /** Closes every open context row whose symbol has left the depot. Comparison is
     *  case-insensitive (normalized to upper-case) because {@code position_context} is
     *  case-insensitive at the DB layer (unique index on {@code lower(symbol)}), so a depot
     *  symbol differing only in case from the stored context symbol must still count as held. */
    private void close(Set<String> depotSymbols) {
        Set<String> depotSymbolsUpper = new LinkedHashSet<>();
        for (String symbol : depotSymbols) {
            depotSymbolsUpper.add(symbol.toUpperCase(Locale.ROOT));
        }

        for (PositionContextRow row : contextRepo.findAllOpen(connection)) {
            try {
                if (!depotSymbolsUpper.contains(row.symbol().toUpperCase(Locale.ROOT))) {
                    contextRepo.markClosed(row.id());
                }
            } catch (RuntimeException e) {
                log.warn("position reconcile: close failed for symbol {} (id {}) -- skipping: {}",
                        row.symbol(), row.id(), e.getMessage());
            }
        }
    }

    /** Kill criteria for a verdict, resolved via its contributing prey (mirrors
     *  {@code GroparWebhookController}'s resolution). Null (rather than an empty array) when
     *  there is nothing to attach, so the stored row matches a manually-opened one. */
    private JsonNode resolveKillCriteria(String verdictId) {
        try {
            List<String> preyIds = verdictRepo.contributingPreyIdsById(verdictId);
            if (preyIds.isEmpty()) return null;
            List<String> killCriteria = preyRepo.findByIds(preyIds).stream()
                    .flatMap(p -> p.killCriteria().stream())
                    .distinct()
                    .toList();
            return killCriteria.isEmpty() ? null : mapper.valueToTree(killCriteria);
        } catch (RuntimeException e) {
            // Deliberate inner catch (in addition to backfill()'s outer one): a kill-criteria
            // resolution failure degrades this single field to null rather than skipping the
            // whole row's backfill.
            log.warn("position reconcile: failed to resolve kill criteria for verdict {} -- omitting: {}",
                    verdictId, e.getMessage());
            return null;
        }
    }

    private JsonNode thesisSnapshot(VerdictRepository.LatestVerdictForSymbol v) {
        Map<String, Object> thesis = new LinkedHashMap<>();
        thesis.put("summary", v.summary());
        thesis.put("signals", v.signals());
        thesis.put("risks", v.risks());
        thesis.put("anomalyTypes", v.anomalyTypes());
        thesis.put("horizon", v.horizon());
        return mapper.valueToTree(thesis);
    }
}
