package de.visterion.dracul.voievod;

import de.visterion.dracul.marketdata.MarketDataPort;
import de.visterion.dracul.prey.Prey;
import de.visterion.dracul.prey.PreyRepository;
import de.visterion.dracul.verdict.ContributingStrigoiDetail;
import de.visterion.dracul.verdict.VerdictRepository;
import de.visterion.dracul.webhook.BearerTokenVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@RestController
@ConditionalOnProperty(value = "dracul.voievod.enabled", havingValue = "true")
@RequestMapping("/api/voievod")
public class VoievodWebhookController {

    private static final Logger log = LoggerFactory.getLogger(VoievodWebhookController.class);
    private static final String USER = "default";

    private final BearerTokenVerifier verifier;
    private final ConsensusDetector detector;
    private final PreyRepository preyRepo;
    private final VerdictRepository verdictRepo;
    private final MarketDataPort marketData;

    public VoievodWebhookController(
            @Value("${dracul.voievod.webhook-token}") String token,
            ConsensusDetector detector,
            PreyRepository preyRepo,
            VerdictRepository verdictRepo,
            MarketDataPort marketData) {
        this.verifier = new BearerTokenVerifier(token);
        this.detector = detector;
        this.preyRepo = preyRepo;
        this.verdictRepo = verdictRepo;
        this.marketData = marketData;
    }

    @PostMapping("/tools/fetch-candidates")
    public ResponseEntity<Map<String, Object>> fetchCandidates(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody(required = false) Map<String, Object> body) {
        if (!verifier.verify(auth)) return ResponseEntity.status(401).build();

        var clusters = detector.detect(preyRepo.findAllByUser(USER), LocalDate.now());
        var wire = new ArrayList<Map<String, Object>>();
        for (var c : clusters) {
            var preyWire = new ArrayList<Map<String, Object>>();
            for (Prey p : c.prey()) {
                preyWire.add(Map.of(
                        "discoveredBy", p.discoveredBy(),
                        "anomalyType", p.anomalyType(),
                        "confidence", p.confidence(),
                        "thesis", p.thesis(),
                        "signals", p.signals(),
                        "risks", p.risks(),
                        "horizon", p.horizon(),
                        "discoveredAt", p.discoveredAt()));
            }
            wire.add(Map.of("symbol", c.symbol(), "companyName", c.companyName(), "prey", preyWire));
        }
        return ResponseEntity.ok(Map.of("output", Map.of("clusters", wire)));
    }

    @PostMapping("/complete")
    public ResponseEntity<Void> complete(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestHeader(value = "X-Vistierie-Run-Id", required = false) String runId,
            @RequestBody JsonNode body) {
        if (!verifier.verify(auth)) return ResponseEntity.status(401).build();

        String status = body.path("status").asText("");
        if (!"done".equals(status) && !"succeeded".equals(status)) {
            log.warn("voievod run {} status={} — acknowledging without persisting", runId, status);
            return ResponseEntity.noContent().build();
        }
        JsonNode verdicts = body.path("output").path("verdicts");
        if (!verdicts.isArray() || verdicts.isEmpty()) {
            log.info("voievod run {} produced no verdicts", runId);
            return ResponseEntity.noContent().build();
        }

        var clusters = detector.detect(preyRepo.findAllByUser(USER), LocalDate.now());
        var bySymbol = new HashMap<String, ConsensusCluster>();
        for (var c : clusters) bySymbol.put(c.symbol(), c);

        int persisted = 0;
        for (JsonNode v : verdicts) {
            String symbol = v.path("symbol").asText("").toUpperCase();
            if (symbol.isBlank()) continue;
            String summary = v.path("summary").asText("");
            var cluster = bySymbol.get(symbol);
            if (cluster == null) {
                log.info("voievod run {} emitted '{}' which no longer qualifies — skipping", runId, symbol);
                continue;
            }
            if (upsert(symbol, summary, cluster)) persisted++;
        }
        log.info("voievod run {} persisted/updated {} verdicts", runId, persisted);
        return ResponseEntity.noContent().build();
    }

    /** Returns true if a verdict was inserted or updated. */
    private boolean upsert(String symbol, String summary, ConsensusCluster cluster) {
        var prey = cluster.prey();
        var confidences = prey.stream().map(Prey::confidence).toList();
        double consensusScore = ConsensusScorer.noisyOr(confidences);
        double avgConfidence = ConsensusScorer.mean(confidences);

        var contributingStrigoi = prey.stream().map(Prey::discoveredBy).distinct().toList();
        var anomalyTypes = prey.stream().map(Prey::anomalyType).distinct().toList();
        var signals = prey.stream().flatMap(p -> p.signals().stream()).distinct().toList();
        var risks = prey.stream().flatMap(p -> p.risks().stream()).distinct().toList();
        var details = prey.stream()
                .map(p -> new ContributingStrigoiDetail(p.discoveredBy(), p.confidence(), p.thesis()))
                .toList();
        var preyIds = prey.stream().map(Prey::id).toList();
        String horizon = Horizons.longest(prey.stream().map(Prey::horizon).toList());
        BigDecimal price = resolvePrice(symbol);

        var active = verdictRepo.findActiveBySymbol(symbol, USER);
        if (active.isPresent()) {
            var a = active.get();
            if (a.decision() != null) {
                log.info("voievod: '{}' already decided ({}) — not overwriting", symbol, a.decision());
                return false;
            }
            if (sameSet(a.contributingPreyIds(), preyIds)) {
                return false;
            }
            verdictRepo.updateSynthesized(a.id(), cluster.companyName(), contributingStrigoi,
                    consensusScore, summary, anomalyTypes, price, avgConfidence, horizon,
                    signals, risks, details, preyIds, USER);
            return true;
        }
        verdictRepo.insertSynthesized(symbol, cluster.companyName(), contributingStrigoi,
                consensusScore, summary, anomalyTypes, price, avgConfidence, horizon,
                signals, risks, details, preyIds, USER);
        return true;
    }

    private BigDecimal resolvePrice(String symbol) {
        try {
            var md = marketData.resolve(symbol);
            return md == null ? null : md.currentPrice();
        } catch (RuntimeException e) {
            log.warn("voievod: price lookup for {} failed: {}", symbol, e.getMessage());
            return null;
        }
    }

    private boolean sameSet(List<String> a, List<String> b) {
        return new HashSet<>(a).equals(new HashSet<>(b));
    }
}
