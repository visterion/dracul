package de.visterion.dracul.voievod;

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
    private final VerdictSynthesizer synthesizer;

    public VoievodWebhookController(
            @Value("${dracul.voievod.webhook-token}") String token,
            ConsensusDetector detector,
            PreyRepository preyRepo,
            VerdictSynthesizer synthesizer) {
        this.verifier = new BearerTokenVerifier(token);
        this.detector = detector;
        this.preyRepo = preyRepo;
        this.synthesizer = synthesizer;
    }

    @PostMapping("/tools/fetch-candidates")
    public ResponseEntity<Map<String, Object>> fetchCandidates(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody(required = false) Map<String, Object> body) { // tool takes no inputs; body is intentionally ignored (Vistierie still posts a body)
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

        int inserted = 0, updated = 0, noop = 0, decidedSkip = 0, notQualified = 0;
        for (JsonNode v : verdicts) {
            String symbol = v.path("symbol").asText("").toUpperCase();
            if (symbol.isBlank()) continue;
            String summary = v.path("summary").asText("");
            var cluster = bySymbol.get(symbol);
            if (cluster == null) {
                log.info("voievod run {} emitted '{}' which no longer qualifies — skipping", runId, symbol);
                notQualified++;
                continue;
            }
            switch (synthesizer.upsert(symbol, summary, cluster, USER)) {
                case INSERTED -> inserted++;
                case UPDATED -> updated++;
                case NOOP_UNCHANGED -> noop++;
                case SKIPPED_DECIDED -> decidedSkip++;
            }
        }
        log.info("voievod run {} — inserted={} updated={} noop={} decided-skip={} not-qualified={}",
                runId, inserted, updated, noop, decidedSkip, notQualified);
        return ResponseEntity.noContent().build();
    }
}
