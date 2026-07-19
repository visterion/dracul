package de.visterion.dracul.renfield;

import de.visterion.dracul.events.SseBroadcaster;
import de.visterion.dracul.hivemem.HiveMemResearchService;
import de.visterion.dracul.notify.TelegramNotifier;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Completion webhook for the renfield daily review. Single-owner by design (spec §B4):
 * proposals always belong to the primary user — no per-watcher fan-out, which keeps
 * UNIQUE (run_id, symbol) consistent. Delivery is idempotent against Vistierie's
 * webhook retries: Telegram + SSE fire only when at least one row was actually
 * inserted. Accepted edge: if the first attempt inserts rows but Dracul dies before
 * Telegram, the retry inserts zero rows and no Telegram is ever sent for that run —
 * the proposals are still in the table/UI. NO auto-trade: persist + report only.
 */
@RestController
@ConditionalOnProperty(value = "dracul.renfield.enabled", havingValue = "true")
@RequestMapping("/api/renfield")
public class RenfieldWebhookController {

    private static final Logger log = LoggerFactory.getLogger(RenfieldWebhookController.class);
    private static final Set<String> ACTIONS =
            Set.of("buy", "add", "trim", "sell", "hold", "drop_from_watchlist");

    private final BearerTokenVerifier verifier;
    private final String owner;
    private final TradeProposalRepository proposals;
    private final TelegramNotifier notifier;
    private final SseBroadcaster broadcaster;
    private final HiveMemResearchService memory;

    public RenfieldWebhookController(
            @Value("${dracul.renfield.webhook-token}") String token,
            @Value("${dracul.primary-user-email:}") String primaryUser,
            TradeProposalRepository proposals,
            TelegramNotifier notifier,
            SseBroadcaster broadcaster,
            HiveMemResearchService memory) {
        this.verifier = new BearerTokenVerifier(token);
        this.owner = primaryUser == null || primaryUser.isBlank() ? "default" : primaryUser;
        this.proposals = proposals;
        this.notifier = notifier;
        this.broadcaster = broadcaster;
        this.memory = memory;
    }

    @PostMapping("/complete")
    public ResponseEntity<Void> complete(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestHeader(value = "X-Vistierie-Run-Id", required = false) String runId,
            @RequestBody JsonNode body) {
        if (!verifier.verify(auth)) return ResponseEntity.status(401).build();

        // Vistierie's successful agent-run status is "done" (AgentRunner); "succeeded"
        // is kept for defensive compatibility with tests/fixtures. "ok" is NOT success.
        String status = body.path("status").asText("");
        if (!"done".equals(status) && !"succeeded".equals(status)) {
            log.warn("renfield run {} status={} — acknowledging without persisting", runId, status);
            return ResponseEntity.noContent().build();
        }

        JsonNode o = body.path("output");
        String marketNote = o.path("market_note").asText("");
        var valid = new ArrayList<JsonNode>();
        for (JsonNode p : o.path("proposals")) {
            String symbol = p.path("symbol").asText("");
            String action = p.path("action").asText("");
            if (symbol.isBlank() || !ACTIONS.contains(action)) {
                log.warn("renfield run {} malformed proposal (symbol='{}', action='{}') — dropping",
                        runId, symbol, action);
                continue;
            }
            valid.add(p);
        }

        if (valid.isEmpty()) {
            // Still tell the user the run happened (spec §B4).
            notifier.notifyDigest("🧾 Renfield Watchlist-Review — keine Vorschläge heute.\n"
                    + marketNote);
            return ResponseEntity.noContent().build();
        }

        int inserted = 0;
        for (JsonNode p : valid) {
            String symbol = p.path("symbol").asText();
            String action = p.path("action").asText();
            String rationale = p.path("rationale").asText("");
            BigDecimal confidence = p.path("confidence").isNumber()
                    ? new BigDecimal(p.path("confidence").asText()) : null;
            int rowsInserted = proposals.insert(owner, symbol, action,
                    p.path("entry_zone").asText(""), p.path("stop").asText(""), confidence,
                    rationale, marketNote, runId);
            inserted += rowsInserted;
            if (rowsInserted > 0) {
                // Cell-only write-back (T1.6 Task 9): per-proposal, not per-batch -- no
                // research_memory_link row (proposals never resolve to a Task-10 outcome the
                // way prey do). Best-effort: writeThesisMemory is itself guarded/never-throwing;
                // the outer try/catch is defense-in-depth so a bug here can't 500 the completion.
                try {
                    memory.writeThesisMemory("trade_proposal", symbol, action, rationale,
                            List.of(), List.of(), List.of(), null, "renfield",
                            confidence == null ? 0.0 : confidence.doubleValue(), runId);
                } catch (RuntimeException e) {
                    log.warn("renfield run {} — memory write for {} failed unexpectedly: {}",
                            runId, symbol, e.getMessage());
                }
            }
        }

        if (inserted > 0) {
            notifier.notifyDigest(render(valid, marketNote));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("count", valid.size());
            payload.put("run_id", runId);
            payload.put("ts", Instant.now().toString());
            broadcaster.sendToOwner(owner, "proposal.new", payload);
        } else {
            log.info("renfield run {} retried delivery — 0 new rows, suppressing Telegram/SSE", runId);
        }
        log.info("renfield run {} persisted {} of {} proposal(s)", runId, inserted, valid.size());
        return ResponseEntity.noContent().build();
    }

    /** ONE bundled plain-text message per run (no parse_mode; German per convention). */
    private static String render(List<JsonNode> valid, String marketNote) {
        StringBuilder sb = new StringBuilder();
        sb.append("🧾 Renfield Watchlist-Review — ").append(valid.size()).append(" Vorschläge\n");
        for (JsonNode p : valid) {
            sb.append("• ").append(p.path("action").asText().toUpperCase())
              .append(' ').append(p.path("symbol").asText());
            String zone = p.path("entry_zone").asText("");
            if (!zone.isBlank()) sb.append(" — Zone ").append(zone);
            String stop = p.path("stop").asText("");
            if (!stop.isBlank()) sb.append(", Stop ").append(stop);
            if (p.path("confidence").isNumber()) {
                sb.append(" (conf ").append(p.path("confidence").asText()).append(')');
            }
            sb.append('\n').append("  ").append(p.path("rationale").asText("")).append('\n');
        }
        if (!marketNote.isBlank()) sb.append("Marktnotiz: ").append(marketNote).append('\n');
        return sb.toString();
    }
}
