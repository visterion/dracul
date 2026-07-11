package de.visterion.dracul.voievod;

import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.MarketDataException;
import de.visterion.dracul.marketdata.OhlcBar;
import de.visterion.dracul.pattern.PatternRepository;
import de.visterion.dracul.prey.Prey;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.webhook.BearerTokenVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Fetch- and completion-side of the voievod-outcome agent: surfaces prey whose horizon
 * elapsed more than 30 days ago and that haven't yet been reviewed, condensed with
 * price history since discovery; on completion, persists the agent's proposed lessons
 * as PENDING {@code patterns} rows. Does NOT extend
 * {@link de.visterion.dracul.webhook.HuntController} — this agent produces patterns,
 * not prey.
 */
@RestController
@ConditionalOnProperty(value = "dracul.voievod-outcome.enabled", havingValue = "true")
@RequestMapping("/api/voievod-outcome")
public class VoievodOutcomeController {

    private static final Logger log = LoggerFactory.getLogger(VoievodOutcomeController.class);
    private static final String USER = "default";
    private static final int CAP = 25;
    /** Horizon is considered "elapsed" once it closed more than this many days ago. */
    private static final int ELAPSED_GRACE_DAYS = 30;

    private final BearerTokenVerifier verifier;
    private final PreyRepository preyRepo;
    private final AgoraMarketData marketData;
    private final PatternRepository patternRepo;

    public VoievodOutcomeController(
            @Value("${dracul.voievod-outcome.webhook-token}") String token,
            PreyRepository preyRepo,
            AgoraMarketData marketData,
            PatternRepository patternRepo) {
        this.verifier = new BearerTokenVerifier(token);
        this.preyRepo = preyRepo;
        this.marketData = marketData;
        this.patternRepo = patternRepo;
    }

    @PostMapping("/tools/fetch-elapsed-prey")
    public ResponseEntity<Map<String, Object>> fetchElapsedPrey(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody(required = false) Map<String, Object> body) {
        if (!verifier.verify(auth)) return ResponseEntity.status(401).build();

        Integer lookbackDays = lookbackDays(body);
        LocalDate today = LocalDate.now();

        List<Prey> unreviewed = preyRepo.findElapsedUnreviewed(USER, lookbackDays);
        List<Prey> elapsed = new ArrayList<>();
        for (Prey p : unreviewed) {
            if (!Horizons.isOpen(p.discoveredAt(), p.horizon(), today.minusDays(ELAPSED_GRACE_DAYS))) {
                elapsed.add(p);
            }
        }

        boolean capped = elapsed.size() > CAP;
        List<Prey> batch = capped ? elapsed.subList(0, CAP) : elapsed;

        var wire = new ArrayList<Map<String, Object>>();
        var reviewedIds = new ArrayList<String>();
        for (Prey p : batch) {
            wire.add(Map.ofEntries(
                    Map.entry("symbol", p.symbol()),
                    Map.entry("anomalyType", p.anomalyType()),
                    Map.entry("thesis", p.thesis()),
                    Map.entry("killCriteria", p.killCriteria()),
                    Map.entry("discoveredAt", p.discoveredAt()),
                    Map.entry("horizon", p.horizon()),
                    Map.entry("ohlc", condensedOhlc(p))));
            reviewedIds.add(p.id());
        }

        // Mark reviewed at fetch time (v1 simplification — see project notes).
        preyRepo.markOutcomeReviewed(reviewedIds);

        return ResponseEntity.ok(Map.of("output", Map.of(
                "prey", wire,
                "cap", CAP,
                "capped", capped)));
    }

    @PostMapping("/complete")
    public ResponseEntity<Void> complete(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestHeader(value = "X-Vistierie-Run-Id", required = false) String runId,
            @RequestBody JsonNode body) {
        if (!verifier.verify(auth)) return ResponseEntity.status(401).build();
        String status = body.path("status").asText("");
        if (!"done".equals(status) && !"succeeded".equals(status)) {
            log.warn("voievod-outcome run {} status={} — acknowledging without persisting", runId, status);
            return ResponseEntity.noContent().build();
        }

        int inserted = 0;
        int skipped = 0;
        for (JsonNode node : body.path("output").path("patterns")) {
            String appliesToStrigoi = node.path("applies_to_strigoi").asText(null);
            String statement = node.path("statement").asText(null);
            if (appliesToStrigoi == null || statement == null) continue;

            int evidenceCount = node.path("evidence_symbols").size();
            if (patternRepo.existsPendingStatement(USER, statement)) {
                skipped++;
                continue;
            }
            patternRepo.insertProposal(USER, appliesToStrigoi, statement, evidenceCount);
            inserted++;
        }
        log.info("voievod-outcome run {} persisted {} pattern proposals ({} duplicates skipped)",
                runId, inserted, skipped);
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> condensedOhlc(Prey p) {
        int days = Horizons.approxDays(p.horizon()) + ELAPSED_GRACE_DAYS;
        List<OhlcBar> bars;
        try {
            bars = marketData.dailyOhlcHistory(p.symbol(), days);
        } catch (MarketDataException e) {
            log.warn("voievod-outcome: OHLC unavailable for {} — {}", p.symbol(), e.getMessage());
            return Map.of();
        }
        if (bars == null || bars.isEmpty()) return Map.of();

        var first = bars.get(0).close();
        var last = bars.get(bars.size() - 1).close();
        var min = bars.stream().map(OhlcBar::close).min(Comparator.naturalOrder()).orElse(first);
        var max = bars.stream().map(OhlcBar::close).max(Comparator.naturalOrder()).orElse(first);
        return Map.of("firstClose", first, "lastClose", last, "minClose", min, "maxClose", max);
    }

    private Integer lookbackDays(Map<String, Object> body) {
        if (body != null && body.get("input") instanceof Map<?, ?> in
                && in.get("lookback_days") instanceof Number n) {
            return n.intValue();
        }
        return null;
    }
}
